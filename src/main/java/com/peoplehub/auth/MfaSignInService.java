package com.peoplehub.auth;

import com.peoplehub.audit.AuditDetails;
import com.peoplehub.audit.AuditEvent;
import com.peoplehub.audit.AuditTarget;
import com.peoplehub.audit.AuditWriter;
import com.peoplehub.common.logging.ActorId;
import com.peoplehub.common.logging.OrganizationId;
import com.peoplehub.mfa.MfaChallenges;
import com.peoplehub.mfa.MfaChallenges.Challenge;
import com.peoplehub.mfa.MfaChallenges.Purpose;
import com.peoplehub.mfa.MfaEnrollmentResponse;
import com.peoplehub.mfa.MfaEnrollmentService;
import com.peoplehub.mfa.MfaEnrollmentService.ConfirmOutcome;
import com.peoplehub.mfa.MfaEnrollmentService.StartOutcome;
import com.peoplehub.mfa.MfaVerifier;
import java.net.InetAddress;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The MFA step of a sign-in (b2-7, B2-7/9, B2-7/10, B2-7/11, B2-7/22, B2-7/25; Spec 8.3): completes
 * a challenge that {@link LoginService} created after a correct password.
 *
 * <ul>
 *   <li>{@link #challenge}: an enrolled person proves a TOTP code (replay-protected by the stored
 *       time step) or a single-use recovery code.
 *   <li>{@link #startEnrollment} and {@link #confirmEnrollment}: a person the policy requires to
 *       have MFA, who has not enrolled, enrolls through the same steps as a signed-in enrollment
 *       ({@link MfaEnrollmentService}) before any session exists.
 * </ul>
 *
 * Every request is resolved only through its challenge token, which carries the organization and
 * employee; the request names neither. The employee row is locked first (while still {@code
 * ACTIVE}), then the challenge row, then the account's lockout is checked, in the lock order of
 * login and deactivation (B2-6/12).
 *
 * <p>A wrong code counts toward the per-account lockout and toward the challenge's own limit of
 * {@value MfaChallenges#MAX_FAILED_ATTEMPTS} (B2-7/10); both counts are committed before the error
 * is returned, so the transaction is explicit rather than {@code @Transactional}. An unknown,
 * expired, used or invalidated challenge, an account that is no longer active or is locked, and a
 * challenge exhausted by wrong codes all end the same way: sign in again.
 *
 * <p>Success consumes the challenge, clears the failed sign-in count (only now: B2-7/10), opens the
 * session with the device label of the password step, and writes {@code LOGIN_SUCCEEDED}, in one
 * transaction. The address of the password step is never compared with this request's.
 */
@Service
public class MfaSignInService {

    /** The outcome of one MFA step. */
    sealed interface Outcome<T> {
        /** Done: a session (or, for {@link #startEnrollment}, the new secret). */
        record Completed<T>(T value) implements Outcome<T> {}

        /** The code was wrong; the challenge can be tried again. */
        record WrongCode<T>(String field) implements Outcome<T> {}

        /** The challenge cannot be used (any more): sign in again. */
        record Ended<T>() implements Outcome<T> {}
    }

    /** A session opened by a completed required enrollment, with the codes to show once. */
    record EnrolledSession(SessionTokens tokens, List<String> recoveryCodes) {

        @Override
        public String toString() {
            return "EnrolledSession[sessionId=" + tokens.sessionId() + "]";
        }
    }

    private static final String SELECT_LOCKED_UNTIL =
            "SELECT locked_until FROM employee WHERE id = ? AND organization_id = ?";

    private final JdbcClient jdbc;
    private final MfaChallenges challenges;
    private final MfaVerifier verifier;
    private final MfaEnrollmentService enrollment;
    private final ActiveEmployeeLock activeEmployeeLock;
    private final FailedSignIns failedSignIns;
    private final RefreshTokenService refreshTokenService;
    private final AuditWriter auditWriter;
    private final TransactionTemplate transactions;

    MfaSignInService(
            JdbcClient jdbc,
            MfaChallenges challenges,
            MfaVerifier verifier,
            MfaEnrollmentService enrollment,
            ActiveEmployeeLock activeEmployeeLock,
            FailedSignIns failedSignIns,
            RefreshTokenService refreshTokenService,
            AuditWriter auditWriter,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.challenges = challenges;
        this.verifier = verifier;
        this.enrollment = enrollment;
        this.activeEmployeeLock = activeEmployeeLock;
        this.failedSignIns = failedSignIns;
        this.refreshTokenService = refreshTokenService;
        this.auditWriter = auditWriter;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * Completes a {@code CHALLENGE} with a TOTP code or, when {@code totpCode} is null, a recovery
     * code.
     */
    Outcome<SessionTokens> challenge(
            String challengeToken, String totpCode, String recoveryCode, InetAddress ip) {
        String field = totpCode != null ? "code" : "recoveryCode";
        return inTransaction(
                challengeToken,
                Purpose.CHALLENGE,
                ip,
                (step) -> {
                    MfaVerifier.Result result =
                            verifier.verify(
                                    step.challenge().organizationId(),
                                    step.challenge().employeeId(),
                                    totpCode,
                                    recoveryCode,
                                    ip);
                    return switch (result) {
                        case TOTP, RECOVERY_CODE ->
                                new Outcome.Completed<>(signIn(step, ip, result.name()));
                        case WRONG -> wrongCode(step, ip, field);
                        case NOT_ENROLLED -> ended(step.challenge());
                    };
                });
    }

    /** Starts the required enrollment of an {@code ENROLL} challenge: the new secret, once. */
    Outcome<MfaEnrollmentResponse> startEnrollment(String challengeToken, InetAddress ip) {
        return inTransaction(
                challengeToken,
                Purpose.ENROLL,
                ip,
                (step) ->
                        switch (enrollment.start(
                                step.challenge().organizationId(), step.challenge().employeeId())) {
                            case StartOutcome.Started started ->
                                    new Outcome.Completed<>(started.response());
                            case StartOutcome.Refused refused -> ended(step.challenge());
                        });
    }

    /** Confirms the required enrollment of an {@code ENROLL} challenge and opens the session. */
    Outcome<EnrolledSession> confirmEnrollment(String challengeToken, String code, InetAddress ip) {
        return inTransaction(
                challengeToken,
                Purpose.ENROLL,
                ip,
                (step) ->
                        switch (enrollment.confirm(
                                step.challenge().organizationId(),
                                step.challenge().employeeId(),
                                code,
                                ip)) {
                            case ConfirmOutcome.Enabled enabled ->
                                    new Outcome.Completed<>(
                                            new EnrolledSession(
                                                    signIn(step, ip, "ENROLLED"),
                                                    enabled.recoveryCodes()));
                            case ConfirmOutcome.WrongCode wrong -> wrongCode(step, ip, "code");
                            case ConfirmOutcome.Refused refused -> ended(step.challenge());
                        });
    }

    /** A challenge that passed every check, with the employee's current role. */
    private record Step(Challenge challenge, String role) {}

    /**
     * Resolves and locks the challenge in a transaction that always commits, then runs {@code
     * action} on it. Every "cannot be used" case ends here, before any code is looked at.
     */
    private <T> Outcome<T> inTransaction(
            String challengeToken,
            Purpose purpose,
            InetAddress ip,
            Function<Step, Outcome<T>> action) {
        return transactions.execute(
                status -> {
                    Optional<Challenge> found = challenges.findOpen(challengeToken, purpose);
                    if (found.isEmpty()) {
                        return new Outcome.Ended<T>();
                    }
                    Challenge challenge = found.get();
                    Optional<String> role =
                            activeEmployeeLock.forLogin(
                                    challenge.employeeId(), challenge.organizationId());
                    Optional<Challenge> locked = challenges.lockOpen(challenge);
                    if (locked.isEmpty()) {
                        return new Outcome.Ended<T>();
                    }
                    if (role.isEmpty() || isLocked(challenge)) {
                        return ended(challenge);
                    }
                    // The password was proven: this request acts for the challenge's employee.
                    ActorId.set(challenge.employeeId().toString());
                    OrganizationId.set(challenge.organizationId());
                    return action.apply(new Step(locked.get(), role.get()));
                });
    }

    private boolean isLocked(Challenge challenge) {
        Optional<Instant> lockedUntil =
                jdbc.sql(SELECT_LOCKED_UNTIL)
                        .param(challenge.employeeId())
                        .param(challenge.organizationId())
                        .query(
                                (rs, rowNum) ->
                                        Optional.ofNullable(rs.getTimestamp("locked_until"))
                                                .map(Timestamp::toInstant))
                        .single();
        return failedSignIns.isLocked(lockedUntil.orElse(null));
    }

    private <T> Outcome<T> wrongCode(Step step, InetAddress ip, String field) {
        Challenge challenge = step.challenge();
        failedSignIns.recordFailure(challenge.employeeId(), challenge.organizationId(), ip);
        return challenges.recordWrongCode(challenge)
                ? new Outcome.WrongCode<>(field)
                : new Outcome.Ended<>();
    }

    private <T> Outcome<T> ended(Challenge challenge) {
        challenges.invalidate(challenge);
        return new Outcome.Ended<>();
    }

    private SessionTokens signIn(Step step, InetAddress ip, String mfaMethod) {
        Challenge challenge = step.challenge();
        challenges.consume(challenge);
        failedSignIns.clear(challenge.employeeId(), challenge.organizationId());
        SessionTokens tokens =
                refreshTokenService.start(
                        challenge.organizationId(),
                        challenge.employeeId(),
                        step.role(),
                        challenge.deviceLabel());
        auditWriter.append(
                AuditEvent.builder(challenge.organizationId(), "LOGIN_SUCCEEDED")
                        .target(AuditTarget.of("EMPLOYEE", challenge.employeeId().toString()))
                        .ip(ip)
                        .details(
                                AuditDetails.builder()
                                        .attribute("sessionId", tokens.sessionId().toString())
                                        .attribute("mfaMethod", mfaMethod)
                                        .build())
                        .build());
        return tokens;
    }
}
