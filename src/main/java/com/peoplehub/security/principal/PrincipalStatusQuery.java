package com.peoplehub.security.principal;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The per-request database check behind every authenticated call (b2-3, B2-3/14): one indexed
 * query, never cached. A token is only honoured while its employee is {@code ACTIVE}, its
 * organization is {@code ACTIVE}, and its session (refresh-token family) still has a usable token,
 * so logout, reuse detection and (from b2-6) deactivation cut off an access token immediately
 * instead of when it expires (D26).
 *
 * <p>The employee is looked up by id <em>and</em> organization together, so a token whose {@code
 * org} claim does not match the employee's real organization finds nothing.
 */
@Component
public class PrincipalStatusQuery {

    private static final String SELECT_ACTIVE_ROLE =
            "SELECT e.role FROM employee e JOIN organization o ON o.id = e.organization_id"
                    + " WHERE e.id = ? AND e.organization_id = ?"
                    + " AND e.status = 'ACTIVE' AND o.status = 'ACTIVE'"
                    + " AND EXISTS (SELECT 1 FROM refresh_token rt WHERE rt.family_id = ?"
                    + " AND rt.employee_id = e.id AND rt.organization_id = e.organization_id"
                    + " AND NOT rt.revoked AND rt.expires_at > ?)";

    private final JdbcClient jdbc;
    private final Clock clock;

    public PrincipalStatusQuery(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** The employee's current role, or empty when the token must not be honoured any more. */
    public Optional<String> activeRole(UUID employeeId, UUID organizationId, UUID sessionId) {
        return jdbc.sql(SELECT_ACTIVE_ROLE)
                .param(employeeId)
                .param(organizationId)
                .param(sessionId)
                .param(Timestamp.from(clock.instant()))
                .query(String.class)
                .optional();
    }
}
