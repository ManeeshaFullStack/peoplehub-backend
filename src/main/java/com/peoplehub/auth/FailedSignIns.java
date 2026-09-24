package com.peoplehub.auth;

import com.peoplehub.audit.AuditDetails;
import com.peoplehub.audit.AuditEvent;
import com.peoplehub.audit.AuditTarget;
import com.peoplehub.audit.AuditWriter;
import com.peoplehub.security.AccountLockout;
import java.net.InetAddress;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-account failed sign-in state (b2-5, B2-5/P3, P4, R2, R3): {@code employee.failed_login_count}
 * and {@code employee.locked_until} (V16). Used by login now and by change-password (R5).
 *
 * <p>A failure increments the count in one atomic update (parallel failures are all counted) and,
 * once the count reaches the threshold, locks the account for {@link AccountLockout}'s backoff and
 * records {@code ACCOUNT_LOCKED}. Attempts made while the account is locked are not passed here:
 * they neither count nor extend the lock (R3). A success clears both.
 *
 * <p>Every query is qualified by the organization as well as the employee (Spec 15.1). Runs in the
 * caller's transaction.
 */
@Component
class FailedSignIns {

    private static final String INCREMENT =
            "UPDATE employee SET failed_login_count = failed_login_count + 1"
                    + " WHERE id = ? AND organization_id = ? RETURNING failed_login_count";

    private static final String LOCK =
            "UPDATE employee SET locked_until = ? WHERE id = ? AND organization_id = ?";

    private static final String CLEAR =
            "UPDATE employee SET failed_login_count = 0, locked_until = NULL"
                    + " WHERE id = ? AND organization_id = ?"
                    + " AND (failed_login_count <> 0 OR locked_until IS NOT NULL)";

    private final JdbcClient jdbc;
    private final AccountLockout lockout;
    private final AuditWriter auditWriter;
    private final Clock clock;

    FailedSignIns(JdbcClient jdbc, AccountLockout lockout, AuditWriter auditWriter, Clock clock) {
        this.jdbc = jdbc;
        this.lockout = lockout;
        this.auditWriter = auditWriter;
        this.clock = clock;
    }

    /** Whether the account is locked right now. */
    boolean isLocked(Instant lockedUntil) {
        return lockedUntil != null && lockedUntil.isAfter(clock.instant());
    }

    /** Counts one failure for an account that is not currently locked; may lock it. */
    @Transactional(propagation = Propagation.MANDATORY)
    void recordFailure(UUID employeeId, UUID organizationId, InetAddress ip) {
        int failures =
                jdbc.sql(INCREMENT)
                        .param(employeeId)
                        .param(organizationId)
                        .query(Integer.class)
                        .single();
        Duration lock = lockout.lockFor(failures);
        if (lock.isZero()) {
            return;
        }
        jdbc.sql(LOCK)
                .param(Timestamp.from(clock.instant().plus(lock)))
                .param(employeeId)
                .param(organizationId)
                .update();
        auditWriter.append(
                AuditEvent.builder(organizationId, "ACCOUNT_LOCKED")
                        .target(AuditTarget.of("EMPLOYEE", employeeId.toString()))
                        .ip(ip)
                        .details(
                                AuditDetails.builder()
                                        .attribute("failedLoginCount", failures)
                                        .attribute("lockSeconds", lock.toSeconds())
                                        .build())
                        .build());
    }

    /** Clears the count and any lock after a successful sign-in (a no-op when already clear). */
    @Transactional(propagation = Propagation.MANDATORY)
    void clear(UUID employeeId, UUID organizationId) {
        jdbc.sql(CLEAR).param(employeeId).param(organizationId).update();
    }
}
