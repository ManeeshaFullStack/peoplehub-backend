package com.peoplehub.auth;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The status check that login and refresh make, under a row lock on the employee, just before they
 * create or rotate a refresh token (b2-6, B2-6/12; D26).
 *
 * <p>Deactivation locks the employee row {@code FOR UPDATE} and then revokes every token. With the
 * employee row locked here too, the two cannot interleave: a login or refresh that started first
 * finishes first and its new token is revoked by the deactivation; one that comes second waits for
 * the deactivation to commit, then sees {@code DEACTIVATED} and fails like any other inactive
 * account. So a completed deactivation never leaves a usable session behind.
 *
 * <p>Lock order, to rule out deadlocks: the employee row always before refresh-token rows, as
 * deactivation, password change and password reset already do. Refresh takes a shared lock, so
 * several sessions of one employee can refresh at once. Login takes {@code FOR NO KEY UPDATE}
 * because it may go on to update the same row (clearing the failed sign-in count), and two logins
 * each holding a shared lock and then updating would deadlock; logins of one employee therefore run
 * one after the other, after their password checks. The per-request check on every API call stays
 * lock-free ({@code PrincipalStatusQuery}).
 */
@Component
class ActiveEmployeeLock {

    private static final String ACTIVE_ROLE =
            "SELECT e.role FROM employee e JOIN organization o ON o.id = e.organization_id"
                    + " WHERE e.id = ? AND e.organization_id = ?"
                    + " AND e.status = 'ACTIVE' AND o.status = 'ACTIVE'";

    private final JdbcClient jdbc;

    ActiveEmployeeLock(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** For a refresh: the current role while active, the employee row share-locked; else empty. */
    Optional<String> forRefresh(UUID employeeId, UUID organizationId) {
        return activeRole(ACTIVE_ROLE + " FOR SHARE OF e", employeeId, organizationId);
    }

    /**
     * For a login: whether still active, the employee row locked against deactivation; else empty.
     */
    Optional<String> forLogin(UUID employeeId, UUID organizationId) {
        return activeRole(ACTIVE_ROLE + " FOR NO KEY UPDATE OF e", employeeId, organizationId);
    }

    private Optional<String> activeRole(String sql, UUID employeeId, UUID organizationId) {
        return jdbc.sql(sql).param(employeeId).param(organizationId).query(String.class).optional();
    }
}
