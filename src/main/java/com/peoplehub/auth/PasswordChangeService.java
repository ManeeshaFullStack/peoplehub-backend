package com.peoplehub.auth;

import com.peoplehub.audit.AuditDetails;
import com.peoplehub.audit.AuditEvent;
import com.peoplehub.audit.AuditTarget;
import com.peoplehub.audit.AuditWriter;
import com.peoplehub.common.api.error.ApiFieldError;
import com.peoplehub.common.api.error.ApiProblemException;
import com.peoplehub.common.api.error.ProblemType;
import com.peoplehub.security.PasswordHasher;
import com.peoplehub.security.PasswordSecurityValidator;
import com.peoplehub.security.principal.AuthenticatedPrincipal;
import java.net.InetAddress;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Change password for the signed-in employee (b2-5, B2-5/P9, R5; Spec 8.2).
 *
 * <p>The new password must match its confirmation and pass the same policy as everywhere else. The
 * current password is then checked with one Argon2 verification:
 *
 * <ul>
 *   <li>Right: the new password's hash is stored, every <em>other</em> session of the employee is
 *       ended ({@code PASSWORD_CHANGED}; the calling session stays signed in) and {@code
 *       PASSWORD_CHANGED} is audited, in one transaction.
 *   <li>Wrong: counted toward the same lockout as login (R5), so a stolen session cannot be used to
 *       guess the password; the count is committed before the 400 is returned. While the account is
 *       locked every attempt fails the same way and is neither counted nor extends the lock (R3).
 * </ul>
 *
 * A change does not clear the failed sign-in count or a lock: only a successful login or a reset
 * does (B2-5/P4, P13). The account is always the caller's own; no id is taken from the request.
 */
@Service
public class PasswordChangeService {

    static final String WRONG_CURRENT_PASSWORD = "is incorrect";

    private static final String SELECT_ACCOUNT_FOR_UPDATE =
            "SELECT e.password_hash, e.locked_until, e.name, e.email,"
                    + " o.name AS organization_name"
                    + " FROM employee e JOIN organization o ON o.id = e.organization_id"
                    + " WHERE e.id = ? AND e.organization_id = ?"
                    + " FOR UPDATE OF e";

    private static final String SET_PASSWORD =
            "UPDATE employee SET password_hash = ? WHERE id = ? AND organization_id = ?";

    private final JdbcClient jdbc;
    private final PasswordHasher passwordHasher;
    private final PasswordSecurityValidator passwordSecurityValidator;
    private final FailedSignIns failedSignIns;
    private final RefreshTokenService refreshTokenService;
    private final AuditWriter auditWriter;
    private final TransactionTemplate transactions;

    public PasswordChangeService(
            JdbcClient jdbc,
            PasswordHasher passwordHasher,
            PasswordSecurityValidator passwordSecurityValidator,
            FailedSignIns failedSignIns,
            RefreshTokenService refreshTokenService,
            AuditWriter auditWriter,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.passwordHasher = passwordHasher;
        this.passwordSecurityValidator = passwordSecurityValidator;
        this.failedSignIns = failedSignIns;
        this.refreshTokenService = refreshTokenService;
        this.auditWriter = auditWriter;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * Changes the caller's password, or throws a validation problem. The transaction is explicit,
     * not {@code @Transactional}, so a wrong current password's failure count is committed before
     * the error is thrown instead of being rolled back with it.
     */
    public void change(
            AuthenticatedPrincipal caller, ChangePasswordRequest request, InetAddress ip) {
        Boolean changed = transactions.execute(status -> changeInTransaction(caller, request, ip));
        if (!Boolean.TRUE.equals(changed)) {
            throw validationFailure(
                    List.of(new ApiFieldError("currentPassword", WRONG_CURRENT_PASSWORD)));
        }
    }

    /** True when changed; false when the current password was wrong or the account is locked. */
    private boolean changeInTransaction(
            AuthenticatedPrincipal caller, ChangePasswordRequest request, InetAddress ip) {
        Account account =
                jdbc.sql(SELECT_ACCOUNT_FOR_UPDATE)
                        .param(caller.employeeId())
                        .param(caller.organizationId())
                        .query(
                                (rs, rowNum) ->
                                        new Account(
                                                rs.getString("password_hash"),
                                                toInstant(rs.getTimestamp("locked_until")),
                                                rs.getString("name"),
                                                rs.getString("email"),
                                                rs.getString("organization_name")))
                        .single();

        if (!request.newPassword().equals(request.confirmPassword())) {
            throw validationFailure(
                    List.of(new ApiFieldError("confirmPassword", "must match newPassword")));
        }
        List<String> violations =
                passwordSecurityValidator.validate(
                        request.newPassword(),
                        List.of(
                                account.organizationName(),
                                account.name(),
                                localPartOf(account.email())));
        if (!violations.isEmpty()) {
            throw validationFailure(
                    violations.stream().map(v -> new ApiFieldError("newPassword", v)).toList());
        }

        // Exactly one Argon2 check, whether or not the account is locked.
        boolean currentMatches =
                account.passwordHash() != null
                        && passwordHasher.matches(
                                request.currentPassword(), account.passwordHash());
        if (failedSignIns.isLocked(account.lockedUntil())) {
            return false;
        }
        if (!currentMatches) {
            failedSignIns.recordFailure(caller.employeeId(), caller.organizationId(), ip);
            return false;
        }

        jdbc.sql(SET_PASSWORD)
                .param(passwordHasher.hash(request.newPassword()))
                .param(caller.employeeId())
                .param(caller.organizationId())
                .update();
        int revoked =
                refreshTokenService.endOtherSessionsAfterPasswordChange(
                        caller.organizationId(), caller.employeeId(), caller.sessionId());
        auditWriter.append(
                AuditEvent.builder(caller.organizationId(), "PASSWORD_CHANGED")
                        .target(AuditTarget.of("EMPLOYEE", caller.employeeId().toString()))
                        .ip(ip)
                        .details(
                                AuditDetails.builder()
                                        .attribute("revokedSessions", revoked)
                                        .build())
                        .build());
        return true;
    }

    private static ApiProblemException validationFailure(List<ApiFieldError> errors) {
        return new ApiProblemException(
                ProblemType.VALIDATION_ERROR, "One or more fields are invalid.", errors, Map.of());
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static String localPartOf(String email) {
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }

    private record Account(
            String passwordHash,
            Instant lockedUntil,
            String name,
            String email,
            String organizationName) {

        @Override
        public String toString() {
            return "Account[...]";
        }
    }
}
