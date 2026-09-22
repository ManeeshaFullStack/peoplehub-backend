package com.peoplehub.notification.email;

import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Enqueues rows to {@code email_outbox} (Spec 9.2, 12; b1-1). It is insert-only: {@link #enqueue}
 * is its only operation, it has no update, delete or read methods, and it uses plain JDBC rather
 * than an entity or repository so there is nothing to call {@code save} or {@code delete} on. It
 * never sends anything: delivery, retry/backoff and templates are the b1-2 processor job, which
 * does not exist yet, so a row this writer creates stays {@code PENDING} until that job is built.
 *
 * <p>What comes from where:
 *
 * <ul>
 *   <li>the organization, recipient, type and payload come from the {@link EmailMessage};
 *   <li>the row id, {@code status} ({@code PENDING}), {@code attempts} ({@code 0}) and {@code
 *       created_at} are never supplied: the database generates or defaults all four, and the
 *       runtime role has no privilege to set them (V4).
 * </ul>
 *
 * <p>{@link Propagation#MANDATORY}: an outbox row commits or rolls back with the business change
 * that caused it, so a crash cannot lose it and a rolled-back change cannot leave a stray email
 * behind (Spec 9.2's transactional-outbox requirement). A caller with no transaction gets an error.
 */
@Component
public class EmailOutboxWriter {

    private static final String INSERT =
            "INSERT INTO email_outbox (organization_id, recipient, type, payload)"
                    + " VALUES (?, ?, ?, CAST(? AS jsonb))";

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public EmailOutboxWriter(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Enqueues one email in the caller's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(EmailMessage message) {
        Objects.requireNonNull(message, "message");
        jdbc.sql(INSERT)
                .param(message.organizationId())
                .param(message.recipient())
                .param(message.type())
                .param(message.payload().toJson(json))
                .update();
    }
}
