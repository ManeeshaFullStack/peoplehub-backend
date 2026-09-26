package com.peoplehub.mfa;

import com.peoplehub.security.principal.AuthenticatedPrincipal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * MFA reminders, decided and remembered on the server (b2-7, B2-7/27; MFA/7). A reminder
 * encourages, never blocks.
 *
 * <p>A person is <em>eligible</em> while their organization offers enrollment, they have not
 * enrolled, and the policy does not require MFA of them (a required person meets the requirement at
 * sign-in instead, B2-7/9). An eligible person is reminded until they dismiss it, and again once
 * {@code peoplehub.mfa.reminder-interval} has passed since the dismissal. The dismissal time is
 * kept in {@code employee.mfa_reminder_dismissed_at}, so no client state can reset the timing.
 */
@Component
public class MfaReminders {

    private static final String DISMISS =
            "UPDATE employee SET mfa_reminder_dismissed_at = ?, updated_at = ?"
                    + " WHERE id = ? AND organization_id = ?";

    private final JdbcClient jdbc;
    private final Clock clock;
    private final Duration interval;

    public MfaReminders(
            JdbcClient jdbc,
            Clock clock,
            @Value("${peoplehub.mfa.reminder-interval}") Duration interval) {
        if (interval.isNegative() || interval.isZero()) {
            throw new IllegalStateException("peoplehub.mfa.reminder-interval must be positive");
        }
        this.jdbc = jdbc;
        this.clock = clock;
        this.interval = interval;
    }

    /**
     * Whether to show the reminder now.
     *
     * @param dismissedAt the last dismissal, or {@code null} if never dismissed
     */
    public boolean show(MfaPolicy policy, boolean required, boolean enabled, Instant dismissedAt) {
        if (!policy.offersEnrollment() || required || enabled) {
            return false;
        }
        return dismissedAt == null || !dismissedAt.plus(interval).isAfter(clock.instant());
    }

    /** Records that the caller dismissed the reminder, at server time. */
    @Transactional
    public void dismiss(AuthenticatedPrincipal caller) {
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.sql(DISMISS)
                .param(now)
                .param(now)
                .param(caller.employeeId())
                .param(caller.organizationId())
                .update();
    }
}
