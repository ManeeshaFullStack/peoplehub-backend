package com.peoplehub.mfa;

import com.peoplehub.audit.AuditDetails;
import com.peoplehub.audit.AuditEvent;
import com.peoplehub.audit.AuditTarget;
import com.peoplehub.audit.AuditWriter;
import com.peoplehub.security.SecureTokens;
import java.net.InetAddress;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proves an enrolled person's second factor (b2-7, B2-7/5, B2-7/8, B2-7/11): a TOTP code from their
 * app or one of their recovery codes. Used by the sign-in challenge; step-up reuses it.
 *
 * <ul>
 *   <li>TOTP: checked against the decrypted secret, only for time steps later than {@code
 *       employee.mfa_totp_last_step}; the matched step is recorded, so a code is never accepted
 *       twice.
 *   <li>Recovery code: normalized and hashed, then marked used in one conditional update, so it
 *       works exactly once even under concurrency. Audited {@code MFA_RECOVERY_CODE_USED} with the
 *       number of codes left.
 * </ul>
 *
 * The caller holds the employee row's lock and supplies the transaction. Nothing here logs or
 * audits a secret or a code.
 */
@Component
public class MfaVerifier {

    /** The result of one proof. */
    public enum Result {
        TOTP,
        RECOVERY_CODE,
        WRONG,
        /** The person has no enabled MFA (for example it was reset meanwhile). */
        NOT_ENROLLED
    }

    private static final String SELECT_FACTOR =
            "SELECT mfa_enabled, mfa_totp_secret, mfa_totp_last_step FROM employee"
                    + " WHERE id = ? AND organization_id = ?";

    private static final String RECORD_STEP =
            "UPDATE employee SET mfa_totp_last_step = ? WHERE id = ? AND organization_id = ?";

    private static final String USE_RECOVERY_CODE =
            "UPDATE mfa_recovery_code SET used_at = ?"
                    + " WHERE code_hash = ? AND employee_id = ? AND organization_id = ?"
                    + " AND used_at IS NULL AND invalidated_at IS NULL";

    private static final String COUNT_RECOVERY_CODES_LEFT =
            "SELECT count(*) FROM mfa_recovery_code WHERE employee_id = ? AND organization_id = ?"
                    + " AND used_at IS NULL AND invalidated_at IS NULL";

    private final JdbcClient jdbc;
    private final MfaSecretCipher cipher;
    private final SecureTokens secureTokens;
    private final AuditWriter auditWriter;
    private final Clock clock;

    public MfaVerifier(
            JdbcClient jdbc,
            MfaSecretCipher cipher,
            SecureTokens secureTokens,
            AuditWriter auditWriter,
            Clock clock) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.secureTokens = secureTokens;
        this.auditWriter = auditWriter;
        this.clock = clock;
    }

    /**
     * Checks a TOTP code, or else a recovery code (exactly one is expected to be non-null).
     *
     * @param ip the caller's address, for the audit row of a used recovery code
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Result verify(
            UUID organizationId,
            UUID employeeId,
            String totpCode,
            String recoveryCode,
            InetAddress ip) {
        Optional<Factor> factor =
                jdbc.sql(SELECT_FACTOR)
                        .param(employeeId)
                        .param(organizationId)
                        .query(
                                (rs, rowNum) ->
                                        new Factor(
                                                rs.getBoolean("mfa_enabled"),
                                                rs.getString("mfa_totp_secret"),
                                                rs.getObject("mfa_totp_last_step", Long.class)))
                        .optional();
        if (factor.isEmpty() || !factor.get().enabled()) {
            return Result.NOT_ENROLLED;
        }
        if (totpCode != null) {
            return verifyTotp(organizationId, employeeId, factor.get(), totpCode);
        }
        return useRecoveryCode(organizationId, employeeId, recoveryCode, ip);
    }

    private Result verifyTotp(UUID organizationId, UUID employeeId, Factor factor, String code) {
        byte[] secret = cipher.decrypt(factor.encryptedSecret(), organizationId, employeeId);
        OptionalLong step = Totp.verify(secret, code, clock.instant(), factor.lastStep());
        if (step.isEmpty()) {
            return Result.WRONG;
        }
        jdbc.sql(RECORD_STEP)
                .param(step.getAsLong())
                .param(employeeId)
                .param(organizationId)
                .update();
        return Result.TOTP;
    }

    private Result useRecoveryCode(
            UUID organizationId, UUID employeeId, String submitted, InetAddress ip) {
        Optional<String> hash = RecoveryCodes.normalize(submitted).map(secureTokens::hash);
        if (hash.isEmpty()) {
            return Result.WRONG;
        }
        Instant now = clock.instant();
        int used =
                jdbc.sql(USE_RECOVERY_CODE)
                        .param(Timestamp.from(now))
                        .param(hash.get())
                        .param(employeeId)
                        .param(organizationId)
                        .update();
        if (used == 0) {
            return Result.WRONG;
        }
        long left =
                jdbc.sql(COUNT_RECOVERY_CODES_LEFT)
                        .param(employeeId)
                        .param(organizationId)
                        .query(Long.class)
                        .single();
        auditWriter.append(
                AuditEvent.builder(organizationId, "MFA_RECOVERY_CODE_USED")
                        .target(AuditTarget.of("EMPLOYEE", employeeId.toString()))
                        .ip(ip)
                        .details(AuditDetails.builder().attribute("codesLeft", left).build())
                        .build());
        return Result.RECOVERY_CODE;
    }

    private record Factor(boolean enabled, String encryptedSecret, Long lastStep) {

        @Override
        public String toString() {
            return "Factor[...]";
        }
    }
}
