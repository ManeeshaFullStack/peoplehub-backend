package com.peoplehub.audit;

import com.peoplehub.common.api.correlation.CorrelationId;
import com.peoplehub.common.logging.ActorId;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.sql.Types;
import java.util.Objects;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Appends rows to the append-only {@code audit_log} (Spec 12, 15; B0-6). It is insert-only: {@link
 * #append} is its only operation, it has no update, delete or read methods, and it uses plain JDBC
 * rather than an entity or repository so there is nothing to call {@code save} or {@code delete}
 * on. The database enforces the same rule independently (runtime privileges and a trigger).
 *
 * <p>What comes from where:
 *
 * <ul>
 *   <li>the actor is {@link ActorId#current()}: the employee id, {@code anonymous}, or {@code
 *       job:<name>}. The caller cannot pass one, so it cannot name someone else. Outside a request
 *       or job there is no actor and appending fails rather than writing an unattributed row;
 *   <li>the correlation id is {@link CorrelationId#current()}, or none;
 *   <li>the organization, action, target, address and details come from the {@link AuditEvent};
 *   <li>the row id and the time are never supplied: the database generates both, and the runtime
 *       role has no privilege to set them.
 * </ul>
 *
 * <p>{@link Propagation#MANDATORY}: an audit row commits or rolls back with the change it
 * describes, so the log never records something that did not happen. A caller with no transaction
 * gets an error. (Audit events that have no business transaction, if any are ever designed, get
 * their own explicit operation then.)
 */
@Component
public class AuditWriter {

    private static final String INSERT =
            "INSERT INTO audit_log (organization_id, actor_id, action, target_type, target_id, ip,"
                    + " correlation_id, details) VALUES (?, ?, ?, ?, ?, CAST(? AS inet), ?,"
                    + " CAST(? AS jsonb))";

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public AuditWriter(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * Appends one audit row in the caller's transaction.
     *
     * @throws IllegalStateException if there is no actor in the current request or job
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(AuditEvent event) {
        Objects.requireNonNull(event, "event");
        String actorId = ActorId.current();
        if (actorId == null) {
            throw new IllegalStateException(
                    "No actor in context: audit rows are always attributed (see ActorId)");
        }
        AuditTarget target = event.target();
        jdbc.sql(INSERT)
                .param(event.organizationId())
                .param(text(actorId))
                .param(text(event.action()))
                .param(text(target == null ? null : target.type()))
                .param(text(target == null ? null : target.id()))
                .param(text(address(event.ip())))
                .param(text(CorrelationId.current()))
                .param(text(event.details().toJson(json)))
                .update();
    }

    /**
     * A typed text parameter, so a {@code null} is bound as a text NULL. (Not {@code param(String,
     * int)}: with a String first argument Java picks the named-parameter overload.)
     */
    private static SqlParameterValue text(String value) {
        return new SqlParameterValue(Types.VARCHAR, value);
    }

    /** The address as text for the inet column, without an IPv6 scope id ({@code %eth0}). */
    private static String address(InetAddress ip) {
        if (ip == null) {
            return null;
        }
        String text = ip.getHostAddress();
        if (ip instanceof Inet6Address) {
            int scope = text.indexOf('%');
            if (scope >= 0) {
                text = text.substring(0, scope);
            }
        }
        return text;
    }
}
