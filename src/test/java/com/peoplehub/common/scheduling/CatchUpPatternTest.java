package com.peoplehub.common.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import com.peoplehub.common.scheduling.testsupport.DailyCloseExample;
import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.PrivilegedFixture;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proof of the catch-up rule for scheduled jobs (Spec 2, 14.3, 22): after downtime the next run
 * repairs everything that was missed, and running again does nothing. A controllable clock stands
 * in for the passing of time and for the downtime itself.
 */
@IntegrationTest
class CatchUpPatternTest {

    private static final ZoneId UTC = ZoneOffset.UTC;
    private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata"); // UTC+5:30, no DST

    @Autowired @PrivilegedFixture private JdbcTemplate jdbc;

    private final SettableClock clock = new SettableClock();

    @BeforeEach
    void createState() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS catchup_days (day DATE PRIMARY KEY)");
        jdbc.update("DELETE FROM catchup_days");
    }

    @AfterEach
    void dropState() {
        jdbc.execute("DROP TABLE IF EXISTS catchup_days");
    }

    private DailyCloseExample job(ZoneId orgZone) {
        return new DailyCloseExample(jdbc, clock, orgZone);
    }

    private List<LocalDate> closedDays() {
        return jdbc.queryForList("SELECT day FROM catchup_days ORDER BY day", LocalDate.class);
    }

    @Test
    void closesEachDayAsItEnds() {
        DailyCloseExample job = job(UTC);

        clock.set("2026-03-10T10:00:00Z");
        assertThat(job.run()).isEqualTo(1);
        assertThat(closedDays()).containsExactly(LocalDate.parse("2026-03-09"));

        clock.set("2026-03-11T00:05:00Z");
        assertThat(job.run()).isEqualTo(1);
        assertThat(closedDays())
                .containsExactly(LocalDate.parse("2026-03-09"), LocalDate.parse("2026-03-10"));
    }

    @Test
    void afterDowntimeOneRunClosesEveryMissedDay() {
        DailyCloseExample job = job(UTC);
        clock.set("2026-03-10T10:00:00Z");
        job.run(); // closed through 03-09

        // The application is down for three days: no run happens on the 11th, 12th or 13th.
        clock.set("2026-03-14T01:00:00Z");
        int closed = job.run();

        assertThat(closed).as("days repaired by a single run").isEqualTo(4);
        assertThat(closedDays())
                .containsExactly(
                        LocalDate.parse("2026-03-09"),
                        LocalDate.parse("2026-03-10"),
                        LocalDate.parse("2026-03-11"),
                        LocalDate.parse("2026-03-12"),
                        LocalDate.parse("2026-03-13"));
    }

    @Test
    void runningAgainAtTheSameTimeChangesNothing() {
        DailyCloseExample job = job(UTC);
        clock.set("2026-03-14T01:00:00Z");
        job.run();
        List<LocalDate> before = closedDays();

        assertThat(job.run()).isZero();
        assertThat(job.run()).isZero();

        assertThat(closedDays()).isEqualTo(before);
    }

    @Test
    void twoRunsAtTheSameMomentCloseEachDayExactlyOnce() throws Exception {
        DailyCloseExample instanceA = job(UTC);
        DailyCloseExample instanceB = job(UTC);
        clock.set("2026-03-10T10:00:00Z");
        instanceA.run();
        clock.set("2026-03-20T10:00:00Z"); // ten missed days

        CyclicBarrier together = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> a =
                    pool.submit(
                            () -> {
                                together.await();
                                return instanceA.run();
                            });
            Future<Integer> b =
                    pool.submit(
                            () -> {
                                together.await();
                                return instanceB.run();
                            });

            assertThat(a.get() + b.get())
                    .as("each missed day closed once across both runs")
                    .isEqualTo(10);
        } finally {
            pool.shutdownNow();
        }
        assertThat(closedDays()).hasSize(11).doesNotHaveDuplicates();
    }

    @Test
    void theOrgTimezoneDecidesWhenADayIsOver() {
        // 20:00 UTC on the 10th is already 01:30 on the 11th in Kolkata.
        clock.set("2026-03-10T20:00:00Z");

        job(UTC).run();
        assertThat(closedDays())
                .as("org in UTC: the 10th is still running")
                .containsExactly(LocalDate.parse("2026-03-09"));

        jdbc.update("DELETE FROM catchup_days");
        job(KOLKATA).run();
        assertThat(closedDays())
                .as("org in Kolkata: the 10th is already over")
                .containsExactly(LocalDate.parse("2026-03-10"));
    }

    /** A clock the test moves by hand. */
    private static final class SettableClock extends Clock {

        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void set(String instant) {
            this.now = Instant.parse(instant);
        }

        @Override
        public ZoneId getZone() {
            return UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone); // the job reads it once per run
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
