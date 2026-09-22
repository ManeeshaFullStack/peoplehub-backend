package com.peoplehub.notification.email;

import com.peoplehub.notification.inapp.NotificationMessage;
import com.peoplehub.notification.inapp.NotificationPayload;
import com.peoplehub.notification.inapp.NotificationWriter;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Turns a suppression event into an Admin-facing in-app notification, reusing the b1-3 {@link
 * NotificationWriter}/SSE pipeline exactly as it stands (Spec 9.1: "email-send failures (to Admin)"
 * is one of the events meant to be mirrored in-app; a suppression is that event, made concrete).
 *
 * <p><b>Not yet called by anything with real data (b1-4).</b> {@link NotificationMessage} requires
 * a real organization id <em>and</em> a real employee id, never null, never fabricated -- the same
 * discipline {@code EmailMessage}/{@code AuditEvent} already enforce. There is currently no way to
 * resolve "which employee is Admin for this organization": that concept does not exist until
 * B2/B3's employee/role model. {@code email_suppression} is also deliberately global (V7, no
 * organization_id), so even the tenant half of that identity is not always available -- the webhook
 * path has none at all, and {@link EmailOutboxProcessor}'s synchronous path was deliberately not
 * extended to source one, to keep its own b1-4 change surgical (see its Javadoc).
 *
 * <p>This class is fully built and tested so a future caller -- once a real Admin identity exists
 * -- can use it immediately. The same "infrastructure ready, no real caller yet" position {@code
 * EmailOutboxWriter} (b1-1) and {@code NotificationWriter} itself (b1-3) were both in when they
 * first merged.
 */
@Component
public class EmailSuppressionNotifier {

    static final String NOTIFICATION_TYPE = "EMAIL_SUPPRESSED";

    private final NotificationWriter notificationWriter;

    public EmailSuppressionNotifier(NotificationWriter notificationWriter) {
        this.notificationWriter = notificationWriter;
    }

    /**
     * Must run in the caller's transaction, same requirement as {@link NotificationWriter#append}.
     */
    public void notifyAdmin(UUID organizationId, UUID adminEmployeeId, SuppressionReason reason) {
        notificationWriter.append(
                NotificationMessage.builder(organizationId, adminEmployeeId, NOTIFICATION_TYPE)
                        .payload(
                                NotificationPayload.builder()
                                        .attribute("reason", reason.name())
                                        .build())
                        .build());
    }
}
