package com.peoplehub.notification.inapp;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Publishes a live nudge to Redis after a notification row commits (b1-3).
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} guarantees this only runs if the
 * transaction {@link NotificationWriter} wrote in actually committed -- a standard Spring idiom,
 * not a distributed transaction across Postgres and Redis.
 *
 * <p><b>At-least-once delivery, not exactly-once, and not the durable record.</b> The {@code
 * notification} row is the durable source of truth; this publish is a best-effort live push. If the
 * process crashes between the DB commit and this listener running, or Redis is briefly unreachable,
 * the row still exists and a client that lists/refreshes will see it -- SSE is a live nudge on top
 * of that, not the only way to learn about it, the same "email is the fallback if in-app is missed"
 * asymmetry Spec 9.2 already describes, mirrored the other direction.
 */
@Component
public class NotificationRedisPublisher {

    private final StringRedisTemplate redis;
    private final JsonMapper json;

    public NotificationRedisPublisher(StringRedisTemplate redis, JsonMapper json) {
        this.redis = redis;
        this.json = json;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onNotificationCreated(NotificationCreatedEvent event) {
        String channel = NotificationChannel.of(event.organizationId(), event.employeeId());
        redis.convertAndSend(channel, toJson(event));
    }

    /** Only safe identifying fields -- never the notification's payload. */
    private String toJson(NotificationCreatedEvent event) {
        ObjectNode node = json.createObjectNode();
        node.put("id", event.notificationId());
        node.put("type", event.type());
        node.put("createdAt", event.createdAt().toString());
        return json.writeValueAsString(node);
    }
}
