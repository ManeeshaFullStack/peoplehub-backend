package com.peoplehub.notification.inapp;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One in-app notification to write (Spec 9.1, 9.2; b1-3). Mirrors {@code EmailMessage}'s shape:
 * only what the caller legitimately knows; the row id, {@code read} and {@code created_at} are
 * generated or defaulted by the database (V6).
 *
 * <p>Neither {@code organizationId} nor {@code employeeId} has a placeholder: both must be real
 * (never null, never the nil UUID). Until B2 creates organizations and employees no production code
 * can supply either, so nothing calls the writer with real data yet -- the same position {@code
 * EmailMessage}/{@code EmailOutboxWriter} were in after b1-1.
 *
 * @param organizationId the tenant the notification belongs to
 * @param employeeId the recipient
 * @param type UPPER_SNAKE_CASE event code, at most 64 characters (Spec 9.1)
 * @param payload structured data; {@link NotificationPayload#none()} when there is none
 */
public record NotificationMessage(
        UUID organizationId, UUID employeeId, String type, NotificationPayload payload) {

    private static final UUID NIL = new UUID(0L, 0L);

    static final Pattern TYPE_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    public NotificationMessage {
        if (organizationId == null || NIL.equals(organizationId)) {
            throw new IllegalArgumentException(
                    "A notification needs a real organization id (not null, not the nil UUID)");
        }
        if (employeeId == null || NIL.equals(employeeId)) {
            throw new IllegalArgumentException(
                    "A notification needs a real employee id (not null, not the nil UUID)");
        }
        if (type == null || !TYPE_CODE.matcher(type).matches()) {
            throw new IllegalArgumentException(
                    "Notification type must match " + TYPE_CODE.pattern());
        }
        if (payload == null) {
            payload = NotificationPayload.none();
        }
    }

    public static Builder builder(UUID organizationId, UUID employeeId, String type) {
        return new Builder(organizationId, employeeId, type);
    }

    public static final class Builder {

        private final UUID organizationId;
        private final UUID employeeId;
        private final String type;
        private NotificationPayload payload = NotificationPayload.none();

        private Builder(UUID organizationId, UUID employeeId, String type) {
            this.organizationId = organizationId;
            this.employeeId = employeeId;
            this.type = type;
        }

        public Builder payload(NotificationPayload payload) {
            this.payload = payload;
            return this;
        }

        public NotificationMessage build() {
            return new NotificationMessage(organizationId, employeeId, type, payload);
        }
    }
}
