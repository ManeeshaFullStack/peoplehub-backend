package com.peoplehub.notification.inapp;

import java.time.Instant;
import java.util.Objects;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes rows to {@code notification} (Spec 9, 12; b1-3). Insert-only, same shape as {@link
 * com.peoplehub.notification.email.EmailOutboxWriter}: {@link #append} is its only operation, plain
 * JDBC, no update/delete/read methods.
 *
 * <p>{@link Propagation#MANDATORY}: a notification row commits or rolls back with the business
 * change it describes, mirroring the outbox's transactional guarantee.
 *
 * <p>After the row is written it publishes a {@link NotificationCreatedEvent} through Spring's
 * {@link ApplicationEventPublisher}. This happens synchronously inside the same transaction, but
 * {@link NotificationRedisPublisher} only acts on it after that transaction actually commits (a
 * standard Spring idiom, not a distributed transaction): a rolled-back write is never seen by
 * Redis.
 */
@Component
public class NotificationWriter {

    private static final String INSERT =
            "INSERT INTO notification (organization_id, employee_id, type, payload)"
                    + " VALUES (?, ?, ?, CAST(? AS jsonb)) RETURNING id, created_at";

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final ApplicationEventPublisher events;

    public NotificationWriter(JdbcClient jdbc, JsonMapper json, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.json = json;
        this.events = events;
    }

    /** Writes one notification in the caller's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(NotificationMessage message) {
        Objects.requireNonNull(message, "message");
        Generated generated =
                jdbc.sql(INSERT)
                        .param(message.organizationId())
                        .param(message.employeeId())
                        .param(message.type())
                        .param(message.payload().toJson(json))
                        .query(
                                (rs, rowNum) ->
                                        new Generated(
                                                rs.getLong("id"),
                                                rs.getTimestamp("created_at").toInstant()))
                        .single();
        events.publishEvent(
                new NotificationCreatedEvent(
                        message.organizationId(),
                        message.employeeId(),
                        generated.id(),
                        message.type(),
                        generated.createdAt()));
    }

    private record Generated(long id, Instant createdAt) {}
}
