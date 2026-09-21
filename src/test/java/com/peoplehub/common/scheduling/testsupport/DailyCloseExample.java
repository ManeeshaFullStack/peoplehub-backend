package com.peoplehub.common.scheduling.testsupport;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The catch-up pattern every scheduled job follows (Spec 2, 14.3): the job does not count on being
 * run once per day. Each run looks at what is already done in the database ("days closed so far")
 * and does everything that is due and missing, so after downtime the next run repairs the gap, and
 * running twice does no harm.
 *
 * <p>"Due" is decided in the <em>org timezone</em> (Spec 4.2): a day is over when it is over for
 * the organisation, not for the server. The zone is passed in here; the real jobs read it from org
 * settings (B2). Time comes from the injected {@link Clock}, so the behaviour is testable.
 *
 * <p>This is a teaching example that lives in the tests. Real jobs (day split in B4, accrual in B9,
 * and so on) copy the shape and ship their own proof test for it.
 */
public class DailyCloseExample {

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ZoneId orgZone;

    public DailyCloseExample(JdbcTemplate jdbc, Clock clock, ZoneId orgZone) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.orgZone = orgZone;
    }

    /** Closes every finished org-local day not closed yet. Returns how many it closed. */
    public int run() {
        LocalDate today = LocalDate.now(clock.withZone(orgZone));
        LocalDate lastClosed =
                jdbc.queryForObject("SELECT max(day) FROM catchup_days", LocalDate.class);
        // First ever run: there is no history to repair, so only yesterday is due.
        LocalDate next = lastClosed == null ? today.minusDays(1) : lastClosed.plusDays(1);

        int closed = 0;
        for (LocalDate day = next; day.isBefore(today); day = day.plusDays(1)) {
            // Idempotent even if two instances ever raced past the lock: the primary key decides.
            closed +=
                    jdbc.update(
                            "INSERT INTO catchup_days(day) VALUES (?) ON CONFLICT DO NOTHING", day);
        }
        return closed;
    }
}
