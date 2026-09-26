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
import org.springframework.transaction.annotation.Propagation;
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
 * <p>{@link #start} and {@link #confirm(UUID, UUID, String, InetAddress)} are the same two steps by
 * account id, returning outcomes instead of throwing, for the required enrollment at sign-in
 * (B2-7/9), which runs them inside its own transaction.
 *
 * <p>From a signed-in session a wrong confirmation code is a plain validation error and does not
 * count toward the sign-in lockout: the caller already holds the secret the code is derived from,
 * so there is nothing to guess. (At sign-in, before a session exists, the challenge counts it:
 * B2-7/10.) Nothing here logs or audits a secret, URI or code.
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

    /** The outcome of starting an enrollment. */
    public sealed interface StartOutcome {
        /** A new pending secret, to be shown once. */
        record Started(MfaEnrollmentResponse response) implements StartOutcome {}

        /** Enrollment is not possible now; {@code detail} says why. */
        record Refused(String detail) implements StartOutcome {}
    }

    /** The outcome of confirming an enrollment. */
    public sealed interface ConfirmOutcome {
        /** MFA is enabled; the recovery codes are to be shown once. */
        record Enabled(List<String> recoveryCodes) implements ConfirmOutcome {

            @Override
            public String toString() {
                return "Enabled[...]";
            }
        }

        /** The code does not match the pending secret; nothing changed. */
        record WrongCode() implements ConfirmOutcome {}

        /** There is nothing to confirm; {@code detail} says why. */
        record Refused(String detail) implements ConfirmOutcome {}
    }

    /** Starts (or restarts) the caller's enrollment and returns the new secret, once. */
    @Transactional
    public MfaEnrollmentResponse enroll(AuthenticatedPrincipal caller) {
        return switch (start(caller.organizationId(), caller.employeeId())) {
            case StartOutcome.Started started -> started.response();
            case StartOutcome.Refused refused -> throw conflict(refused.detail());
        };
    }

    /**
     * Confirms the caller's pending enrollment with a code from the app; returns the codes once.
     */
    @Transactional
    public MfaRecoveryCodesResponse confirm(
            AuthenticatedPrincipal caller, String code, InetAddress ip) {
        return switch (confirm(caller.organizationId(), caller.employeeId(), code, ip)) {
            case ConfirmOutcome.Enabled enabled ->
                    new MfaRecoveryCodesResponse(enabled.recoveryCodes());
            case ConfirmOutcome.WrongCode wrong ->
                    throw new ApiProblemException(
                            ProblemType.VALIDATION_ERROR,
                            "One or more fields are invalid.",
                            List.of(new ApiFieldError("code", WRONG_CODE)),
                            Map.of());
            case ConfirmOutcome.Refused refused -> throw conflict(refused.detail());
        };
    }

    /**
     * Starts an enrollment for an account, in the caller's transaction: from the signed-in {@link
     * #enroll} or from a required enrollment at sign-in (B2-7/9). Locks the employee row.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public StartOutcome start(UUID organizationId, UUID employeeId) {
        Account account = lockAccount(organizationId, employeeId);
        if (!account.policy().offersEnrollment()) {
            return new StartOutcome.Refused(NOT_OFFERED);
        }
        if (account.enabled()) {
            return new StartOutcome.Refused(ALREADY_ENABLED);
        }
        byte[] secret = Totp.newSecret();
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.sql(SET_PENDING_SECRET)
                .param(cipher.encrypt(secret, organizationId, employeeId))
                .param(now)
                .param(employeeId)
                .param(organizationId)
                .update();
        return new StartOutcome.Started(
                new MfaEnrollmentResponse(
                        Totp.displaySecret(secret),
                        Totp.otpauthUri(appName, accountName(account), secret)));
    }

    /**
     * Confirms an account's pending enrollment, in the caller's transaction: enables MFA, replaces
     * the recovery codes and audits {@code MFA_ENROLLED}. A wrong code changes nothing. Locks the
     * employee row.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ConfirmOutcome confirm(
            UUID organizationId, UUID employeeId, String code, InetAddress ip) {
        Account account = lockAccount(organizationId, employeeId);
        if (!account.policy().offersEnrollment()) {
            return new ConfirmOutcome.Refused(NOT_OFFERED);
        }
        if (account.enabled()) {
            return new ConfirmOutcome.Refused(ALREADY_ENABLED);
        }
        if (account.encryptedSecret() == null) {
            return new ConfirmOutcome.Refused(NOTHING_TO_CONFIRM);
        }
        byte[] secret = cipher.decrypt(account.encryptedSecret(), organizationId, employeeId);
        Instant now = clock.instant();
        OptionalLong step = Totp.verify(secret, code, now, account.lastStep());
        if (step.isEmpty()) {
            return new ConfirmOutcome.WrongCode();
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
        return new ConfirmOutcome.Enabled(codes);
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
