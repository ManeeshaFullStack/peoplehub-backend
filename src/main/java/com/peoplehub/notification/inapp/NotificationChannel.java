package com.peoplehub.notification.inapp;

import java.util.UUID;

/**
 * The Redis pub/sub channel name for one employee's live notifications (b1-3): {@code
 * notification:{organizationId}:{employeeId}}. Tenant-scoped from the start (v9 Spec 12.1: "SSE
 * channels/topics are tenant-scoped"), the same {@code org:{organizationId}:...} naming convention
 * already used for cache keys (Spec 13.3). Shared by {@link NotificationRedisPublisher} (which
 * publishes to it) and {@link NotificationBroadcastService} (which subscribes to it), so the two
 * sides can never drift apart on the format.
 */
final class NotificationChannel {

    private NotificationChannel() {}

    static String of(UUID organizationId, UUID employeeId) {
        return "notification:" + organizationId + ":" + employeeId;
    }
}
