package com.peoplehub.notification.inapp;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-employee, per-type email/in-app toggles (Spec 9.3; b1-3). Security-/approval-critical types
 * (see {@link CriticalNotificationTypes}) cannot be fully disabled: enforced here <em>and</em> by
 * {@code ck_notification_preference_critical_always_on} in V6, the database being the backstop for
 * this application-layer check, not the only place it lives.
 *
 * <p>No preference row means the defaults (both channels on) apply, matching the table's own column
 * defaults -- {@link #get} never returns {@code null}.
 */
@Component
public class NotificationPreferenceService {

    private static final String UPSERT =
            "INSERT INTO notification_preference (organization_id, employee_id, type, email, in_app)"
                    + " VALUES (?, ?, ?, ?, ?)"
                    + " ON CONFLICT (organization_id, employee_id, type)"
                    + " DO UPDATE SET email = EXCLUDED.email, in_app = EXCLUDED.in_app";

    private static final String SELECT =
            "SELECT email, in_app FROM notification_preference"
                    + " WHERE organization_id = ? AND employee_id = ? AND type = ?";

    private final JdbcClient jdbc;

    public NotificationPreferenceService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Preference(boolean email, boolean inApp) {}

    @Transactional
    public void set(
            UUID organizationId, UUID employeeId, String type, boolean email, boolean inApp) {
        if (CriticalNotificationTypes.CRITICAL.contains(type) && !(email && inApp)) {
            throw new IllegalArgumentException(
                    "Notification type '"
                            + type
                            + "' is security-/approval-critical and cannot be"
                            + " disabled");
        }
        jdbc.sql(UPSERT)
                .param(organizationId)
                .param(employeeId)
                .param(type)
                .param(email)
                .param(inApp)
                .update();
    }

    @Transactional(readOnly = true)
    public Preference get(UUID organizationId, UUID employeeId, String type) {
        return jdbc.sql(SELECT)
                .param(organizationId)
                .param(employeeId)
                .param(type)
                .query(
                        (rs, rowNum) ->
                                new Preference(rs.getBoolean("email"), rs.getBoolean("in_app")))
                .optional()
                .orElse(new Preference(true, true));
    }
}
