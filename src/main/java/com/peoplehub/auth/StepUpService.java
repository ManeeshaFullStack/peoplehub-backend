package com.peoplehub.auth;

import com.peoplehub.audit.AuditDetails;
import com.peoplehub.audit.AuditEvent;
import com.peoplehub.audit.AuditTarget;
import com.peoplehub.audit.AuditWriter;
import com.peoplehub.common.api.error.ApiFieldError;
import com.peoplehub.common.api.error.ApiProblemException;
import com.peoplehub.common.api.error.ProblemType;
import com.peoplehub.mfa.MfaVerifier;
import com.peoplehub.mfa.StepUps;
import com.peoplehub.security.PasswordHasher;
import com.peoplehub.security.principal.AuthenticatedPrincipal;
import java.net.InetAddress;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Step-up authentication of the signed-in caller (b2-7, B2-7/14, B2-7/15, B2-7/16; Spec 8.3):
 * {@code POST /me/step-up}.
 *
 * <ul>
 *   <li>MFA enabled: the password <em>and</em> a TOTP code or a recovery code ({@link MfaVerifier}:
 *       a TOTP code is replay-protected by the stored time step, a recovery code works once).
 *   <li>MFA required by the organization's policy but not enrolled: 403 {@code
 *       mfa-enrollment-required}; the caller enrolls first.
 *   <li>Otherwise: the password alone.
 * </ul>
 *
 * Success is recorded against the calling session ({@link StepUps}, V22) with its method and
 * audited {@code STEP_UP_VERIFIED}; it is fresh for five minutes and authorizes no other session.
 *
 * <p>Failures follow change-password (B2-5/P9, R5): exactly one Argon2 check; a wrong password, or
 * a wrong code after a right password, counts toward the per-account lockout (B2-7/10, B2-7/15) and
 * is a 400 on that field; while the account is locked every attempt fails the same way, is not
 * counted and does not look at the code. The counts are committed before the error is returned, so
 * the transaction is explicit. A step-up does not clear the failed sign-in count (only a completed
 * sign-in or a reset does, B2-5/P4). Nothing here logs or audits a password or a code.
 */
@Service
public class StepUpService {

    static final String INCORRECT = "is incorrect";
    static final String ONE_CODE = "exactly one of code and recoveryCode is required";

    private static final String SELECT_ACCOUNT_FOR_UPDATE =
            "SELECT password_hash, locked_until FROM employee"
                    + " WHERE id = ? AND organization_id = ? FOR UPDATE";

    private final JdbcClient jdbc;
    private final PasswordHasher passwordHasher;
    private final FailedSignIns failedSignIns;
    private final MfaVerifier verifier;
    private final StepUps stepUps;
    private final AuditWriter auditWriter;
    private final TransactionTemplate transactions;

    StepUpService(
            JdbcClient jdbc,
            PasswordHasher passwordHasher,
            FailedSignIns failedSignIns,
            MfaVerifier verifier,
            StepUps stepUps,
            AuditWriter auditWriter,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.passwordHasher = passwordHasher;
        this.failedSignIns = failedSignIns;
        this.verifier = verifier;
        this.stepUps = stepUps;
        this.auditWriter = auditWriter;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** Steps up the caller's current session, or throws the matching problem. */
    public void stepUp(AuthenticatedPrincipal caller, StepUpRequest request, InetAddress ip) {
        Optional<String> failedField =
                transactions.execute(status -> stepUpInTransaction(caller, request, ip));
        if (failedField.isPresent()) {
            throw validationFailure(List.of(new ApiFieldError(failedField.get(), INCORRECT)));
        }
    }

    /** Empty on success; otherwise the field that was wrong. */
    private Optional<String> stepUpInTransaction(
            AuthenticatedPrincipal caller, StepUpRequest request, InetAddress ip) {
        Account account =
                jdbc.sql(SELECT_ACCOUNT_FOR_UPDATE)
                        .param(caller.employeeId())
                        .param(caller.organizationId())
                        .query(
                                (rs, rowNum) -> {
                                    Timestamp lockedUntil = rs.getTimestamp("locked_until");
                                    return new Account(
                                            rs.getString("password_hash"),
                                            lockedUntil == null ? null : lockedUntil.toInstant());
                                })
                        .single();
        StepUps.MfaState state = stepUps.mfaState(caller);
        if (!state.enabled() && state.required()) {
            throw new ApiProblemException(
                    ProblemType.MFA_ENROLLMENT_REQUIRED, StepUps.ENROLLMENT_REQUIRED);
        }
        String code = blankToNull(request.code());
        String recoveryCode = blankToNull(request.recoveryCode());
        if (state.enabled() && (code == null) == (recoveryCode == null)) {
            throw validationFailure(
                    List.of(
                            new ApiFieldError("code", ONE_CODE),
                            new ApiFieldError("recoveryCode", ONE_CODE)));
        }

        // Exactly one Argon2 check, whether or not the account is locked.
        boolean passwordMatches =
                account.passwordHash() != null
                        && passwordHasher.matches(request.password(), account.passwordHash());
        if (failedSignIns.isLocked(account.lockedUntil())) {
            return Optional.of("password");
        }
        if (!passwordMatches) {
            failedSignIns.recordFailure(caller.employeeId(), caller.organizationId(), ip);
            return Optional.of("password");
        }

        StepUps.Method method = StepUps.Method.PASSWORD;
        if (state.enabled()) {
            MfaVerifier.Result result =
                    verifier.verify(
                            caller.organizationId(),
                            caller.employeeId(),
                            code,
                            code != null ? null : recoveryCode,
                            ip);
            switch (result) {
                case TOTP -> method = StepUps.Method.PASSWORD_AND_TOTP;
                case RECOVERY_CODE -> method = StepUps.Method.PASSWORD_AND_RECOVERY_CODE;
                case WRONG, NOT_ENROLLED -> {
                    failedSignIns.recordFailure(caller.employeeId(), caller.organizationId(), ip);
                    return Optional.of(code != null ? "code" : "recoveryCode");
                }
            }
        }

        stepUps.record(caller, method);
        auditWriter.append(
                AuditEvent.builder(caller.organizationId(), "STEP_UP_VERIFIED")
                        .target(AuditTarget.of("EMPLOYEE", caller.employeeId().toString()))
                        .ip(ip)
                        .details(
                                AuditDetails.builder()
                                        .attribute("method", method.name())
                                        .attribute("sessionId", caller.sessionId().toString())
                                        .build())
                        .build());
        return Optional.empty();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static ApiProblemException validationFailure(List<ApiFieldError> errors) {
        return new ApiProblemException(
                ProblemType.VALIDATION_ERROR, "One or more fields are invalid.", errors, Map.of());
    }

    private record Account(String passwordHash, Instant lockedUntil) {

        @Override
        public String toString() {
            return "Account[...]";
        }
    }
}
