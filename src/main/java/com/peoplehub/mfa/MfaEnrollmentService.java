package com.peoplehub.mfa;

import com.peoplehub.audit.AuditDetails;
import com.peoplehub.audit.AuditEvent;
import com.peoplehub.audit.AuditTarget;
import com.peoplehub.audit.AuditWriter;
import com.peoplehub.common.api.error.ApiFieldError;
import com.peoplehub.common.api.error.ApiProblemException;
import com.peoplehub.common.api.error.ProblemType;
import com.peoplehub.security.SecureTokens;
import com.peoplehub.security.principal.AuthenticatedPrincipal;
import java.net.InetAddress;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Voluntary TOTP enrollment from a signed-in session (b2-7, B2-7/4, B2-7/6, B2-7/8; Spec 8.3,
 * 13.0): {@code enroll} then {@code confirm}.
 *
 * <ul>
 *   <li>{@link #enroll}: a new 160-bit secret, stored encrypted and <em>pending</em> ({@code
 *       mfa_enabled} stays false), returned once with its {@code otpauth://} URI. A later {@code
 *       enroll} before confirmation simply replaces the pending secret.
 *   <li>{@link #confirm}: a valid code from the app enables MFA, records the enrollment time and
 *       the code's time step (so it cannot be replayed), replaces any earlier recovery codes with
 *       ten new ones returned once, and audits {@code MFA_ENROLLED}, in one transaction.
 * </ul>
 *
 * Both need the organization's policy to offer enrollment (not {@code DISABLED}); under a {@code
 * REQUIRED_*} policy both the people it covers and the others may enroll here. Enrolling again
 * while enrolled needs step-up authentication (B2-7/6, B2-7/14), which arrives with step-up itself;
 * until then it is refused (409). The employee row is locked first, so two parallel calls cannot
 * interleave. The account is always the caller's own, from the token; no id is taken from the
 * request.
 *
 * <p>A wrong confirmation code is a plain validation error and does not count toward the sign-in
 * lockout: the caller already holds the secret the code is derived from, so there is nothing to
 * guess. Nothing here logs or audits a secret, URI or code.
 */
@Service
public class MfaEnrollmentService {

    static final String WRONG_CODE = "is incorrect";
    static final String NOT_OFFERED = "Your organization does not offer MFA.";
    static final String ALREADY_ENABLED = "MFA is already enabled for your account.";
    static final String NOTHING_TO_CONFIRM = "There is no MFA enrollment to confirm; start again.";

    private static final String SELECT_ACCOUNT_FOR_UPDATE =
            "SELECT e.email, e.mfa_enabled, e.mfa_totp_secret, e.mfa_totp_last_step,"
                    + " o.login_key_normalized, o.mfa_policy"
                    + " FROM employee e JOIN organization o ON o.id = e.organization_id"
                    + " WHERE e.id = ? AND e.organization_id = ?"
                    + " FOR UPDATE OF e";

    private static final String SET_PENDING_SECRET =
            "UPDATE employee SET mfa_totp_secret = ?, updated_at = ?"
                    + " WHERE id = ? AND organization_id = ? AND NOT mfa_enabled";

    private static final String ENABLE =
            "UPDATE employee SET mfa_enabled = true, mfa_enrolled_at = ?, mfa_totp_last_step = ?,"
                    + " updated_at = ?"
                    + " WHERE id = ? AND organization_id = ? AND NOT mfa_enabled";

    private static final String INVALIDATE_RECOVERY_CODES =
            "UPDATE mfa_recovery_code SET invalidated_at = ?"
                    + " WHERE employee_id = ? AND organization_id = ?"
                    + " AND used_at IS NULL AND invalidated_at IS NULL";

    private static final String INSERT_RECOVERY_CODE =
            "INSERT INTO mfa_recovery_code (organization_id, employee_id, code_hash)"
                    + " VALUES (?, ?, ?)";

    private final JdbcClient jdbc;
    private final MfaSecretCipher cipher;
    private final RecoveryCodes recoveryCodes;
    private final SecureTokens secureTokens;
    private final AuditWriter auditWriter;
    private final Clock clock;
    private final String appName;

    public MfaEnrollmentService(
            JdbcClient jdbc,
            MfaSecretCipher cipher,
            RecoveryCodes recoveryCodes,
            SecureTokens secureTokens,
            AuditWriter auditWriter,
            Clock clock,
            @Value("${peoplehub.app-name}") String appName) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.recoveryCodes = recoveryCodes;
        this.secureTokens = secureTokens;
        this.auditWriter = auditWriter;
        this.clock = clock;
        this.appName = appName;
    }

    /** Starts (or restarts) the caller's enrollment and returns the new secret, once. */
    @Transactional
    public MfaEnrollmentResponse enroll(AuthenticatedPrincipal caller) {
        Account account = lockAccount(caller.organizationId(), caller.employeeId());
        if (!account.policy().offersEnrollment()) {
            throw conflict(NOT_OFFERED);
        }
        if (account.enabled()) {
            throw conflict(ALREADY_ENABLED);
        }
        byte[] secret = Totp.newSecret();
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.sql(SET_PENDING_SECRET)
                .param(cipher.encrypt(secret, caller.organizationId(), caller.employeeId()))
                .param(now)
                .param(caller.employeeId())
                .param(caller.organizationId())
                .update();
        return new MfaEnrollmentResponse(
                Totp.displaySecret(secret), Totp.otpauthUri(appName, accountName(account), secret));
    }

    /**
     * Confirms the caller's pending enrollment with a code from the app; returns the codes once.
     */
    @Transactional
    public MfaRecoveryCodesResponse confirm(
            AuthenticatedPrincipal caller, String code, InetAddress ip) {
        UUID organizationId = caller.organizationId();
        UUID employeeId = caller.employeeId();
        Account account = lockAccount(organizationId, employeeId);
        if (!account.policy().offersEnrollment()) {
            throw conflict(NOT_OFFERED);
        }
        if (account.enabled()) {
            throw conflict(ALREADY_ENABLED);
        }
        if (account.encryptedSecret() == null) {
            throw conflict(NOTHING_TO_CONFIRM);
        }
        byte[] secret = cipher.decrypt(account.encryptedSecret(), organizationId, employeeId);
        Instant now = clock.instant();
        OptionalLong step = Totp.verify(secret, code, now, account.lastStep());
        if (step.isEmpty()) {
            throw new ApiProblemException(
                    ProblemType.VALIDATION_ERROR,
                    "One or more fields are invalid.",
                    List.of(new ApiFieldError("code", WRONG_CODE)),
                    Map.of());
        }

        Timestamp at = Timestamp.from(now);
        jdbc.sql(ENABLE)
                .param(at)
                .param(step.getAsLong())
                .param(at)
                .param(employeeId)
                .param(organizationId)
                .update();
        jdbc.sql(INVALIDATE_RECOVERY_CODES)
                .param(at)
                .param(employeeId)
                .param(organizationId)
                .update();
        List<String> codes = recoveryCodes.generate();
        for (String recoveryCode : codes) {
            jdbc.sql(INSERT_RECOVERY_CODE)
                    .param(organizationId)
                    .param(employeeId)
                    .param(secureTokens.hash(RecoveryCodes.normalize(recoveryCode).orElseThrow()))
                    .update();
        }
        auditWriter.append(
                AuditEvent.builder(organizationId, "MFA_ENROLLED")
                        .target(AuditTarget.of("EMPLOYEE", employeeId.toString()))
                        .ip(ip)
                        .details(
                                AuditDetails.builder()
                                        .attribute("method", "TOTP")
                                        .attribute("codesIssued", codes.size())
                                        .build())
                        .build());
        return new MfaRecoveryCodesResponse(codes);
    }

    private Account lockAccount(UUID organizationId, UUID employeeId) {
        return jdbc.sql(SELECT_ACCOUNT_FOR_UPDATE)
                .param(employeeId)
                .param(organizationId)
                .query(
                        (rs, rowNum) ->
                                new Account(
                                        rs.getString("email"),
                                        rs.getString("login_key_normalized"),
                                        MfaPolicy.valueOf(rs.getString("mfa_policy")),
                                        rs.getBoolean("mfa_enabled"),
                                        rs.getString("mfa_totp_secret"),
                                        rs.getObject("mfa_totp_last_step", Long.class)))
                .single();
    }

    /**
     * How the authenticator app labels the account: the email plus the organization's login key, so
     * the same email in two organizations gives two distinguishable entries.
     */
    private static String accountName(Account account) {
        return account.email() + " (" + account.organizationLoginKey() + ")";
    }

    private static ApiProblemException conflict(String detail) {
        return new ApiProblemException(ProblemType.CONFLICT, detail);
    }

    private record Account(
            String email,
            String organizationLoginKey,
            MfaPolicy policy,
            boolean enabled,
            String encryptedSecret,
            Long lastStep) {

        @Override
        public String toString() {
            return "Account[...]";
        }
    }
}
