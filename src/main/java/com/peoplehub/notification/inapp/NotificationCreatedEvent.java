package com.peoplehub.notification.inapp;

import java.time.Instant;
import java.util.UUID;

/**
 * Published in-process by {@link NotificationWriter} when a notification row is written (b1-3). A
 * plain POJO event, not a Spring {@code ApplicationEvent} subclass (Spring 4.2+ publishes any
 * object). {@link NotificationRedisPublisher} listens for it with {@code phase =
 * TransactionPhase.AFTER_COMMIT}, so a rolled-back write never reaches Redis.
 *
 * <p>Carries only safe identifying fields, never the notification's {@link NotificationPayload}: a
 * live SSE subscriber only needs to know something arrived (Spec 9.2, "unread count, real-time via
 * SSE"), the same "id and code, never the content" discipline used elsewhere in this repo for
 * cross-cutting channels.
 */
public record NotificationCreatedEvent(
        UUID organizationId,
        UUID employeeId,
        long notificationId,
        String type,
        Instant createdAt) {}
