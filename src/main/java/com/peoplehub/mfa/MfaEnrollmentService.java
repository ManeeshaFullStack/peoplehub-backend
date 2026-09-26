package com.peoplehub.mfa;

import com.peoplehub.audit.AuditDetails;
import com.peoplehub.audit.AuditEvent;
import com.peoplehub.audit.AuditTarget;
import com.peoplehub.audit.AuditWriter;
import com.peoplehub.common.api.error.ApiFieldError;
import com.peoplehub.common.api.error.ApiProblemException;
import com.peoplehub.common.api.error.ProblemType;
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
 * TOTP enrollment from a signed-in session (b2-7, B2-7/4, B2-7/6, B2-7/8, B2-7/14; Spec 8.3, 13.0):
 * {@code enroll} then {@code confirm}.
 *
 * <p><b>First enrollment</b> (not enrolled):
 *
 * <ul>
 *   <li>{@link #enroll}: a new 160-bit secret, stored encrypted and <em>pending</em> in {@code
 *       mfa_totp_secret} ({@code mfa_enabled} stays false), returned once with its {@code
 *       otpauth://} URI. A later {@code enroll} before confirmation replaces the pending secret.
 *   <li>{@link #confirm}: a valid code from the app enables MFA, records the enrollment time and
 *       the code's time step (so it cannot be replayed), replaces any earlier recovery codes with
 *       ten new ones returned once, and audits {@code MFA_ENROLLED}, in one transaction.
 * </ul>
 *
 * <p><b>Re-enrollment</b> (already enrolled, a new authenticator; B2-7/6): {@link #enroll} needs a
 * fresh step-up of this session ({@link StepUps}) and writes the new secret to {@code
 * mfa_totp_pending_secret} (V23) only. The active secret and the recovery codes keep working until
 * {@link #confirm} proves the new one; a later {@code enroll} replaces only the pending secret, and
 * abandoning or failing changes nothing. Confirming, in one transaction: the pending secret becomes
 * the active one and is cleared, the enrollment time is renewed, the replay step becomes the later
 * of the old one and the code's step, every unused recovery code is invalidated and ten new ones
 * are issued, and {@code MFA_ENROLLED} is audited with {@code reenrolled=true}. Both secrets carry
 * the same account binding (B2-7/7), so the ciphertext moves between the columns unchanged.
 *
 * <p>Both need the organization's policy to offer enrollment (not {@code DISABLED}); under a {@code
 * REQUIRED_*} policy both the people it covers and the others may enroll here. The employee row is
 * locked first, so two parallel calls cannot interleave. The account is always the caller's own,
 * from the token; no id is taken from the request.
 *
 * <p>{@link #start} and {@link #confirm(UUID, UUID, String, InetAddress)} are the first-enrollment
 * steps by account id, returning outcomes instead of throwing, for the required enrollment at
 * sign-in (B2-7/9), which runs them inside its own transaction.
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
            "SELECT e.email, e.mfa_enabled, e.mfa_totp_secret, e.mfa_totp_pending_secret,"
                    + " e.mfa_totp_last_step, o.login_key_normalized, o.mfa_policy"
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

    private static final String SET_REENROLLMENT_SECRET =
            "UPDATE employee SET mfa_totp_pending_secret = ?, updated_at = ?"
                    + " WHERE id = ? AND organization_id = ? AND mfa_enabled";

    private static final String PROMOTE_PENDING_SECRET =
            "UPDATE employee SET mfa_totp_secret = mfa_totp_pending_secret,"
                    + " mfa_totp_pending_secret = NULL, mfa_enrolled_at = ?,"
                    + " mfa_totp_last_step = GREATEST(COALESCE(mfa_totp_last_step, ?), ?),"
                    + " updated_at = ?"
                    + " WHERE id = ? AND organization_id = ? AND mfa_enabled"
                    + " AND mfa_totp_pending_secret IS NOT NULL";

    private final JdbcClient jdbc;
    private final MfaSecretCipher cipher;
    private final RecoveryCodeStore recoveryCodeStore;
    private final StepUps stepUps;
    private final AuditWriter auditWriter;
    private final Clock clock;
    private final String appName;

    MfaEnrollmentService(
            JdbcClient jdbc,
            MfaSecretCipher cipher,
            RecoveryCodeStore recoveryCodeStore,
            StepUps stepUps,
            AuditWriter auditWriter,
            Clock clock,
            @Value("${peoplehub.app-name}") String appName) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.recoveryCodeStore = recoveryCodeStore;
        this.stepUps = stepUps;
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

    /**
     * Starts (or restarts) the caller's enrollment and returns the new secret, once. While the
     * caller is enrolled this is a re-enrollment and needs a fresh step-up.
     */
    @Transactional
    public MfaEnrollmentResponse enroll(AuthenticatedPrincipal caller) {
        Account account = lockAccount(caller.organizationId(), caller.employeeId());
        if (!account.policy().offersEnrollment()) {
            throw conflict(NOT_OFFERED);
        }
        if (!account.enabled()) {
            return startFirst(caller.organizationId(), caller.employeeId(), account);
        }
        stepUps.requireFresh(caller);
        byte[] secret = Totp.newSecret();
        jdbc.sql(SET_REENROLLMENT_SECRET)
                .param(cipher.encrypt(secret, caller.organizationId(), caller.employeeId()))
                .param(Timestamp.from(clock.instant()))
                .param(caller.employeeId())
                .param(caller.organizationId())
                .update();
        return response(account, secret);
    }

    /**
     * Confirms the caller's pending enrollment or re-enrollment with a code from the app; returns
     * the new recovery codes, once.
     */
    @Transactional
    public MfaRecoveryCodesResponse confirm(
            AuthenticatedPrincipal caller, String code, InetAddress ip) {
        UUID organizationId = caller.organizationId();
        UUID employeeId = caller.employeeId();
        Account account = lockAccount(organizationId, employeeId);
        ConfirmOutcome outcome =
                account.enabled()
                        ? confirmReenrollment(organizationId, employeeId, account, code, ip)
                        : confirmFirst(organizationId, employeeId, account, code, ip);
        return switch (outcome) {
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
     * Starts a first enrollment for an account, in the caller's transaction: for a required
     * enrollment at sign-in (B2-7/9). Locks the employee row.
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
        return new StartOutcome.Started(startFirst(organizationId, employeeId, account));
    }

    /**
     * Confirms an account's first enrollment, in the caller's transaction: enables MFA, replaces
     * the recovery codes and audits {@code MFA_ENROLLED}. A wrong code changes nothing. Locks the
     * employee row.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ConfirmOutcome confirm(
            UUID organizationId, UUID employeeId, String code, InetAddress ip) {
        Account account = lockAccount(organizationId, employeeId);
        if (account.enabled()) {
            return new ConfirmOutcome.Refused(ALREADY_ENABLED);
        }
        return confirmFirst(organizationId, employeeId, account, code, ip);
    }

    private MfaEnrollmentResponse startFirst(
            UUID organizationId, UUID employeeId, Account account) {
        byte[] secret = Totp.newSecret();
        jdbc.sql(SET_PENDING_SECRET)
                .param(cipher.encrypt(secret, organizationId, employeeId))
                .param(Timestamp.from(clock.instant()))
                .param(employeeId)
                .param(organizationId)
                .update();
        return response(account, secret);
    }

    private ConfirmOutcome confirmFirst(
            UUID organizationId, UUID employeeId, Account account, String code, InetAddress ip) {
        if (!account.policy().offersEnrollment()) {
            return new ConfirmOutcome.Refused(NOT_OFFERED);
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
        return enrolled(organizationId, employeeId, at, ip, false);
    }

    private ConfirmOutcome confirmReenrollment(
            UUID organizationId, UUID employeeId, Account account, String code, InetAddress ip) {
        if (!account.policy().offersEnrollment()) {
            return new ConfirmOutcome.Refused(NOT_OFFERED);
        }
        if (account.encryptedPendingSecret() == null) {
            return new ConfirmOutcome.Refused(NOTHING_TO_CONFIRM);
        }
        byte[] secret =
                cipher.decrypt(account.encryptedPendingSecret(), organizationId, employeeId);
        Instant now = clock.instant();
        // The new secret has never been used, so every step in the window is open to it. The
        // stored step then becomes the later of the two, so neither this code nor any earlier
        // step's code of either secret can be used again.
        OptionalLong step = Totp.verify(secret, code, now, null);
        if (step.isEmpty()) {
            return new ConfirmOutcome.WrongCode();
        }
        Timestamp at = Timestamp.from(now);
        jdbc.sql(PROMOTE_PENDING_SECRET)
                .param(at)
                .param(step.getAsLong())
                .param(step.getAsLong())
                .param(at)
                .param(employeeId)
                .param(organizationId)
                .update();
        return enrolled(organizationId, employeeId, at, ip, true);
    }

    /** Replaces the recovery codes and audits the enrollment. */
    private ConfirmOutcome enrolled(
            UUID organizationId, UUID employeeId, Timestamp at, InetAddress ip, boolean again) {
        List<String> codes = recoveryCodeStore.replace(organizationId, employeeId, at);
        AuditDetails.Builder details =
                AuditDetails.builder()
                        .attribute("method", "TOTP")
                        .attribute("codesIssued", codes.size());
        if (again) {
            details.attribute("reenrolled", true);
        }
        auditWriter.append(
                AuditEvent.builder(organizationId, "MFA_ENROLLED")
                        .target(AuditTarget.of("EMPLOYEE", employeeId.toString()))
                        .ip(ip)
                        .details(details.build())
                        .build());
        return new ConfirmOutcome.Enabled(codes);
    }

    private MfaEnrollmentResponse response(Account account, byte[] secret) {
        return new MfaEnrollmentResponse(
                Totp.displaySecret(secret), Totp.otpauthUri(appName, accountName(account), secret));
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
                                        rs.getString("mfa_totp_pending_secret"),
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
            String encryptedPendingSecret,
            Long lastStep) {

        @Override
        public String toString() {
            return "Account[...]";
        }
    }
}
