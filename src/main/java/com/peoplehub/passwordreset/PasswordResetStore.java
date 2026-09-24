package com.peoplehub.passwordreset;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Plain JDBC access to {@code password_reset_token} (V17) and the employee columns a reset changes,
 * the same no-entity style as {@code InvitationStore}. Only token hashes are ever stored or looked
 * up (B2-5/P8). Every query is qualified by the organization as well as the employee (Spec 15.1).
 */
@Component
class PasswordResetStore {

    private static final String ACTIVE = "ACTIVE";

    /** Only an active employee of an active organization can reset (B2-5/P11). */
    private static final String SELECT_ACTIVE_ACCOUNT_FOR_UPDATE =
            "SELECT e.id, e.organization_id, e.name, e.email, o.login_key_normalized"
                    + " FROM organization o JOIN employee e ON e.organization_id = o.id"
                    + " WHERE o.login_key_normalized = ? AND e.email_normalized = ?"
                    + " AND o.status = 'ACTIVE' AND e.status = 'ACTIVE'"
                    + " FOR UPDATE OF e";

    /** Reset requests in the last day and in the last interval, by the database's clock (P7). */
    private static final String RECENT_REQUESTS =
            "SELECT count(*) AS last_day,"
                    + " count(*) FILTER (WHERE created_at > now() - make_interval(secs => ?))"
                    + " AS last_interval"
                    + " FROM password_reset_token"
                    + " WHERE employee_id = ? AND organization_id = ?"
                    + " AND created_at > now() - interval '24 hours'";

    private static final String INVALIDATE_OPEN =
            "UPDATE password_reset_token SET invalidated_at = ?"
                    + " WHERE employee_id = ? AND organization_id = ?"
                    + " AND consumed_at IS NULL AND invalidated_at IS NULL";

    private static final String INSERT_TOKEN =
            "INSERT INTO password_reset_token (organization_id, employee_id, token_hash,"
                    + " expires_at) VALUES (?, ?, ?, ?) RETURNING id";

    private static final String SELECT_TOKEN_FOR_UPDATE =
            "SELECT t.id, t.organization_id, t.employee_id, t.expires_at, t.consumed_at,"
                    + " t.invalidated_at, e.name, e.email, e.status AS employee_status,"
                    + " o.name AS organization_name, o.status AS organization_status"
                    + " FROM password_reset_token t"
                    + " JOIN employee e ON e.organization_id = t.organization_id"
                    + " AND e.id = t.employee_id"
                    + " JOIN organization o ON o.id = t.organization_id"
                    + " WHERE t.token_hash = ?"
                    + " FOR UPDATE OF t, e";

    private static final String CONSUME =
            "UPDATE password_reset_token SET consumed_at = ?"
                    + " WHERE id = ? AND consumed_at IS NULL AND invalidated_at IS NULL";

    /** A new password also clears the lockout: the reset proved control of the email (P13). */
    private static final String SET_PASSWORD =
            "UPDATE employee SET password_hash = ?, failed_login_count = 0, locked_until = NULL"
                    + " WHERE id = ? AND organization_id = ?";

    private final JdbcClient jdbc;

    PasswordResetStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The active account with this login key and email, row-locked until the transaction ends, so
     * two parallel requests for one account are throttled one after the other.
     */
    Optional<Account> activeAccountForUpdate(String loginKey, String emailNormalized) {
        return jdbc.sql(SELECT_ACTIVE_ACCOUNT_FOR_UPDATE)
                .param(loginKey)
                .param(emailNormalized)
                .query(
                        (rs, rowNum) ->
                                new Account(
                                        rs.getObject("id", UUID.class),
                                        rs.getObject("organization_id", UUID.class),
                                        rs.getString("name"),
                                        rs.getString("email"),
                                        rs.getString("login_key_normalized")))
                .optional();
    }

    RecentRequests recentRequests(UUID employeeId, UUID organizationId, Duration interval) {
        return jdbc.sql(RECENT_REQUESTS)
                .param(interval.toSeconds())
                .param(employeeId)
                .param(organizationId)
                .query(
                        (rs, rowNum) ->
                                new RecentRequests(
                                        rs.getLong("last_day"), rs.getLong("last_interval")))
                .single();
    }

    /** Makes every still-usable reset of this employee unusable (a newer one replaces it, P8). */
    int invalidateOpen(UUID employeeId, UUID organizationId, Instant at) {
        return jdbc.sql(INVALIDATE_OPEN)
                .param(Timestamp.from(at))
                .param(employeeId)
                .param(organizationId)
                .update();
    }

    UUID insert(UUID organizationId, UUID employeeId, String tokenHash, Instant expiresAt) {
        return jdbc.sql(INSERT_TOKEN)
                .param(organizationId)
                .param(employeeId)
                .param(tokenHash)
                .param(Timestamp.from(expiresAt))
                .query(UUID.class)
                .single();
    }

    /** The reset with this token hash and its employee, both row-locked. */
    Optional<Reset> byTokenHashForUpdate(String tokenHash) {
        return jdbc.sql(SELECT_TOKEN_FOR_UPDATE)
                .param(tokenHash)
                .query(PasswordResetStore::reset)
                .optional();
    }

    boolean consume(UUID resetId, Instant at) {
        return jdbc.sql(CONSUME).param(Timestamp.from(at)).param(resetId).update() == 1;
    }

    boolean setPassword(UUID employeeId, UUID organizationId, String passwordHash) {
        return jdbc.sql(SET_PASSWORD)
                        .param(passwordHash)
                        .param(employeeId)
                        .param(organizationId)
                        .update()
                == 1;
    }

    private static Reset reset(ResultSet rs, int rowNum) throws SQLException {
        return new Reset(
                rs.getObject("id", UUID.class),
                rs.getObject("organization_id", UUID.class),
                rs.getObject("employee_id", UUID.class),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("consumed_at") != null,
                rs.getTimestamp("invalidated_at") != null,
                ACTIVE.equals(rs.getString("employee_status"))
                        && ACTIVE.equals(rs.getString("organization_status")),
                rs.getString("name"),
                rs.getString("email"),
                rs.getString("organization_name"));
    }

    record Account(
            UUID employeeId, UUID organizationId, String name, String email, String loginKey) {

        @Override
        public String toString() {
            return "Account[employeeId=" + employeeId + "]";
        }
    }

    record RecentRequests(long lastDay, long lastInterval) {}

    record Reset(
            UUID id,
            UUID organizationId,
            UUID employeeId,
            Instant expiresAt,
            boolean consumed,
            boolean invalidated,
            boolean accountActive,
            String employeeName,
            String employeeEmail,
            String organizationName) {

        /** Unused, unexpired, not replaced, and its account can still sign in. */
        boolean isUsableAt(Instant now) {
            return !consumed && !invalidated && accountActive && expiresAt.isAfter(now);
        }

        @Override
        public String toString() {
            return "Reset[id=" + id + ", employeeId=" + employeeId + "]";
        }
    }
}
