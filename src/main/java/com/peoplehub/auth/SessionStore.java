package com.peoplehub.auth;

import com.peoplehub.common.api.paging.PageQuery;
import com.peoplehub.common.api.paging.SortOrder;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Plain JDBC reads and revocations of one employee's sessions (b2-6, B2-6/1, 2, 4, 5). A session is
 * a refresh-token family; it is active while it has a token that is not revoked and not past its
 * sliding expiry (rotation keeps exactly one such token per family, B2-3/7). Every query is
 * qualified by the employee <em>and</em> the organization, both from the caller's token (Spec
 * 15.1).
 *
 * <p>Revocations first lock the rows they will change, so a refresh running at the same moment
 * finishes first and its new token is revoked too, instead of slipping past the update. Locks are
 * always taken in id order, so two revocations running at once cannot deadlock each other.
 */
@Component
class SessionStore {

    /**
     * The only sort keys a client may use (B2-6/2), mapped to fixed SQL; nothing is interpolated.
     */
    private static final Map<String, String> SORT_COLUMNS =
            Map.of("createdAt", "created_at", "lastUsedAt", "last_used_at");

    private static final String ACTIVE =
            " FROM refresh_token t WHERE t.employee_id = ? AND t.organization_id = ?"
                    + " AND NOT t.revoked AND t.expires_at > ?";

    private static final String COUNT = "SELECT count(*)" + ACTIVE;

    private static final String LIST =
            "SELECT t.family_id, t.device_label, t.created_at AS last_used_at, t.expires_at,"
                    + " t.absolute_expires_at,"
                    + " (SELECT min(f.created_at) FROM refresh_token f"
                    + " WHERE f.family_id = t.family_id) AS created_at"
                    + ACTIVE;

    private static final String LOCK_FAMILY =
            "SELECT id FROM refresh_token WHERE family_id = ? AND employee_id = ?"
                    + " AND organization_id = ? AND NOT revoked ORDER BY id FOR UPDATE";

    private static final String REVOKE_FAMILY =
            "UPDATE refresh_token SET revoked = true, revoked_at = ?, revoke_reason = ?"
                    + " WHERE family_id = ? AND employee_id = ? AND organization_id = ?"
                    + " AND NOT revoked AND expires_at > ?";

    private static final String LOCK_OTHERS =
            "SELECT id FROM refresh_token WHERE employee_id = ? AND organization_id = ?"
                    + " AND family_id <> ? AND NOT revoked ORDER BY id FOR UPDATE";

    private static final String REVOKE_OTHERS =
            "UPDATE refresh_token SET revoked = true, revoked_at = ?, revoke_reason = ?"
                    + " WHERE employee_id = ? AND organization_id = ? AND family_id <> ?"
                    + " AND NOT revoked AND expires_at > ?";

    private final JdbcClient jdbc;

    SessionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    long countActive(UUID employeeId, UUID organizationId, Instant now) {
        return jdbc.sql(COUNT)
                .param(employeeId)
                .param(organizationId)
                .param(Timestamp.from(now))
                .query(Long.class)
                .single();
    }

    List<Session> listActive(UUID employeeId, UUID organizationId, Instant now, PageQuery query) {
        return jdbc.sql(LIST + orderBy(query.sort()) + " LIMIT ? OFFSET ?")
                .param(employeeId)
                .param(organizationId)
                .param(Timestamp.from(now))
                .param(query.size())
                .param((long) query.page() * query.size())
                .query(
                        (rs, rowNum) ->
                                new Session(
                                        rs.getObject("family_id", UUID.class),
                                        rs.getString("device_label"),
                                        rs.getTimestamp("created_at").toInstant(),
                                        rs.getTimestamp("last_used_at").toInstant(),
                                        rs.getTimestamp("expires_at").toInstant(),
                                        rs.getTimestamp("absolute_expires_at").toInstant()))
                .list();
    }

    /** Ends one active session of this employee; false when there is none with that id. */
    boolean revokeActive(
            UUID sessionId,
            UUID employeeId,
            UUID organizationId,
            RefreshTokenStore.RevokeReason reason,
            Instant now) {
        jdbc.sql(LOCK_FAMILY)
                .param(sessionId)
                .param(employeeId)
                .param(organizationId)
                .query(UUID.class)
                .list();
        return jdbc.sql(REVOKE_FAMILY)
                        .param(Timestamp.from(now))
                        .param(reason.name())
                        .param(sessionId)
                        .param(employeeId)
                        .param(organizationId)
                        .param(Timestamp.from(now))
                        .update()
                > 0;
    }

    /** Ends every active session of this employee except one; returns how many ended. */
    int revokeOtherActive(
            UUID keptSessionId,
            UUID employeeId,
            UUID organizationId,
            RefreshTokenStore.RevokeReason reason,
            Instant now) {
        jdbc.sql(LOCK_OTHERS)
                .param(employeeId)
                .param(organizationId)
                .param(keptSessionId)
                .query(UUID.class)
                .list();
        return jdbc.sql(REVOKE_OTHERS)
                .param(Timestamp.from(now))
                .param(reason.name())
                .param(employeeId)
                .param(organizationId)
                .param(keptSessionId)
                .param(Timestamp.from(now))
                .update();
    }

    /**
     * {@code ORDER BY} from whitelisted keys only ({@code @PageParams} already rejected any other),
     * with the session id last so pages are stable.
     */
    private static String orderBy(List<SortOrder> sort) {
        String keys =
                sort.stream()
                        .map(
                                order ->
                                        column(order.property())
                                                + (order.direction().isAscending()
                                                        ? " ASC"
                                                        : " DESC"))
                        .collect(Collectors.joining(", "));
        return " ORDER BY " + (keys.isEmpty() ? "" : keys + ", ") + "family_id";
    }

    private static String column(String property) {
        String column = SORT_COLUMNS.get(property);
        if (column == null) {
            throw new IllegalArgumentException("Not a sortable session field");
        }
        return column;
    }

    record Session(
            UUID sessionId,
            String deviceLabel,
            Instant createdAt,
            Instant lastUsedAt,
            Instant expiresAt,
            Instant absoluteExpiresAt) {}
}
