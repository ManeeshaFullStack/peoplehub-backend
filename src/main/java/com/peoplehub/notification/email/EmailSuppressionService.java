package com.peoplehub.notification.email;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The global email suppression list (b1-4, Spec 9.2, 12): {@link #isSuppressed} is the gate {@link
 * EmailOutboxProcessor} consults before attempting a send; {@link #suppress} is how an address gets
 * onto the list, called both from {@link EmailWebhookController} (bounce/complaint) and from {@link
 * EmailOutboxProcessor} (a synchronous {@code ADDRESS_REJECTED} rejection). Deliberately global,
 * not per-organization (V7): a bad address is bad for every tenant.
 */
@Component
public class EmailSuppressionService {

    private final JdbcClient jdbc;

    public EmailSuppressionService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isSuppressed(String email) {
        Long count =
                jdbc.sql("SELECT count(*) FROM email_suppression WHERE email = ?")
                        .param(email)
                        .query(Long.class)
                        .single();
        return count > 0;
    }

    /** Idempotent: the first suppression for an address wins, a later report is a no-op. */
    public void suppress(String email, SuppressionReason reason) {
        jdbc.sql(
                        "INSERT INTO email_suppression (email, reason) VALUES (?, ?)"
                                + " ON CONFLICT (email) DO NOTHING")
                .param(email)
                .param(reason.name())
                .update();
    }
}
