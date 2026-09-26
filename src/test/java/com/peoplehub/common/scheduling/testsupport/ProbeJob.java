package com.peoplehub.common.scheduling.testsupport;

import com.peoplehub.common.scheduling.JobRunner;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Test-only job that shows the recommended shape of a job and records when each run started and
 * ended (on the database's clock), so tests can prove what the scheduler did: whether two runs ever
 * overlapped, whether a run in flight survived shutdown, whether a failure was reported.
 *
 * <p>Active only under the {@code scheduler-test} profile, so it never runs in normal runs or in
 * {@code spring-boot:test-run}. Tunable through properties so one class serves every test:
 *
 * <ul>
 *   <li>{@code probe.instance}: label stored with each run (which application instance ran it)
 *   <li>{@code probe.every}: delay between runs (ISO-8601, default 200 ms)
 *   <li>{@code probe.work-millis}: how long a run takes (default 300 ms)
 *   <li>{@code probe.fail}: when true the run throws after starting
 *   <li>{@code probe.unwind-millis}: how long the run takes to clean up after being interrupted
 *       (default 0), like a real job finishing what it started
 * </ul>
 *
 * <p>It writes to {@link ProbeJobSchema#TABLE} through the application's own connection (the
 * runtime role, in tests); the test provisions that table beforehand ({@link ProbeJobSchema}).
 */
@Profile("scheduler-test")
@Component
public class ProbeJob {

    public static final String JOB_NAME = "probe-job";
    public static final String SECRET_IN_FAILURE = "jane.doe@example.com";

    private final JobRunner jobRunner;
    private final JdbcTemplate jdbc;
    private final String instance;
    private final long workMillis;
    private final boolean fail;
    private final long unwindMillis;

    public ProbeJob(
            JobRunner jobRunner,
            JdbcTemplate jdbc,
            @Value("${probe.instance:A}") String instance,
            @Value("${probe.work-millis:300}") long workMillis,
            @Value("${probe.fail:false}") boolean fail,
            @Value("${probe.unwind-millis:0}") long unwindMillis) {
        this.jobRunner = jobRunner;
        this.jdbc = jdbc;
        this.instance = instance;
        this.workMillis = workMillis;
        this.fail = fail;
        this.unwindMillis = unwindMillis;
    }

    @Scheduled(fixedDelayString = "${probe.every:PT0.2S}")
    @SchedulerLock(name = JOB_NAME, lockAtMostFor = "PT30S", lockAtLeastFor = "PT0S")
    public void run() {
        jobRunner.run(JOB_NAME, this::work);
    }

    private void work() {
        Long id =
                jdbc.queryForObject(
                        "INSERT INTO "
                                + ProbeJobSchema.TABLE
                                + "(instance, started_at) VALUES (?, clock_timestamp())"
                                + " RETURNING id",
                        Long.class,
                        instance);
        if (fail) {
            throw new IllegalStateException(SECRET_IN_FAILURE);
        }
        try {
            Thread.sleep(workMillis);
        } catch (InterruptedException e) {
            unwind();
            throw new IllegalStateException("interrupted", e);
        }
        jdbc.update(
                "UPDATE " + ProbeJobSchema.TABLE + " SET ended_at = clock_timestamp() WHERE id = ?",
                id);
    }

    /** What a real job does when told to stop: finish what it must, then let the failure out. */
    private void unwind() {
        try {
            Thread.sleep(unwindMillis);
        } catch (InterruptedException ignored) {
            // already stopping
        }
    }
}
