package com.peoplehub.common.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import com.peoplehub.PeopleHubApplication;
import com.peoplehub.support.TestcontainersConfiguration;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The scheduler across real application instances (Spec 2, 14.3, 16.4). Several full applications
 * are started in this JVM against one shared PostgreSQL, each running the same {@code @Scheduled}
 * probe job, and what they did is read back from the database's own clock.
 *
 * <p>These start their own applications rather than using the Spring test context, because the test
 * framework owns (and re-uses) that context, and because two instances need one shared database.
 */
class ScheduledJobInstancesTest {

    // Through the shared factory: V3 grants to the runtime role, so the database needs its roles
    // first.
    static final PostgreSQLContainer POSTGRES = TestcontainersConfiguration.newPostgresContainer();
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse(TestcontainersConfiguration.REDIS_IMAGE))
                    .withExposedPorts(6379);

    @BeforeAll
    static void startContainers() {
        POSTGRES.start();
        REDIS.start();
    }

    @AfterAll
    static void stopContainers() {
        REDIS.stop();
        POSTGRES.stop();
    }

    @BeforeEach
    void cleanDatabaseState() throws Exception {
        // First test creates nothing yet; later tests start from empty probe and lock tables.
        try (Connection c = connect();
                Statement s = c.createStatement()) {
            s.execute(
                    "DO $$ BEGIN IF to_regclass('job_probe') IS NOT NULL THEN DELETE FROM job_probe; END IF; END $$");
            s.execute(
                    "DO $$ BEGIN IF to_regclass('shedlock') IS NOT NULL THEN DELETE FROM shedlock; END IF; END $$");
        }
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static ConfigurableApplicationContext startInstance(String label, String... extraArgs) {
        String[] base = {
            "--server.port=0",
            "--spring.profiles.active=scheduler-test",
            "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
            "--spring.datasource.username=" + POSTGRES.getUsername(),
            "--spring.datasource.password=" + POSTGRES.getPassword(),
            "--spring.data.redis.host=" + REDIS.getHost(),
            "--spring.data.redis.port=" + REDIS.getMappedPort(6379),
            "--probe.instance=" + label
        };
        String[] args = new String[base.length + extraArgs.length];
        System.arraycopy(base, 0, args, 0, base.length);
        System.arraycopy(extraArgs, 0, args, base.length, extraArgs.length);
        return new SpringApplicationBuilder(PeopleHubApplication.class).run(args);
    }

    private static long count(String sql) throws Exception {
        try (Connection c = connect();
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void waitUntil(String description, long timeoutMillis, BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Timed out waiting for: " + description);
            }
            Thread.sleep(100);
        }
    }

    private static boolean atLeastRuns(long n) {
        try {
            return count("SELECT count(*) FROM job_probe WHERE ended_at IS NOT NULL") >= n;
        } catch (Exception e) {
            return false; // table not created yet
        }
    }

    @Test
    void twoInstancesRunningTheSameJobNeverRunItAtTheSameTime() throws Exception {
        ConfigurableApplicationContext a = startInstance("A");
        ConfigurableApplicationContext b = startInstance("B");
        try {
            waitUntil("at least 10 completed runs", 60_000, () -> atLeastRuns(10));
        } finally {
            b.close();
            a.close();
        }

        long overlaps =
                count(
                        "SELECT count(*) FROM job_probe x JOIN job_probe y ON x.id < y.id"
                                + " AND x.started_at < y.ended_at AND y.started_at < x.ended_at");
        long instancesThatRan = count("SELECT count(DISTINCT instance) FROM job_probe");
        assertThat(overlaps).as("runs that overlapped in time").isZero();
        assertThat(instancesThatRan).as("at least one instance ran the job").isPositive();
    }

    private static void waitForRunInFlight(ConfigurableApplicationContext app) throws Exception {
        try {
            waitUntil(
                    "a run to be in flight",
                    30_000,
                    () -> {
                        try {
                            return count("SELECT count(*) FROM job_probe WHERE ended_at IS NULL")
                                    == 1;
                        } catch (Exception e) {
                            return false;
                        }
                    });
        } catch (AssertionError | InterruptedException e) {
            app.close();
            throw e;
        }
    }

    @Test
    void aJobStillRunningWhenTheShutdownWaitEndsIsInterruptedAndReleasesItsLock() throws Exception {
        // A 20 s job and a 2 s shutdown wait: the job cannot finish in time.
        ConfigurableApplicationContext app =
                startInstance(
                        "A",
                        "--probe.work-millis=20000",
                        "--probe.unwind-millis=1500", // a real job needs a moment to clean up
                        "--probe.every=PT30S",
                        "--spring.lifecycle.timeout-per-shutdown-phase=2s");
        waitForRunInFlight(app);

        long started = System.nanoTime();
        app.close();
        long closeMillis = (System.nanoTime() - started) / 1_000_000;

        assertThat(closeMillis)
                .as("shutdown is bounded by the wait (2 s), not held for the whole 20 s job")
                .isLessThan(12_000);
        assertThat(count("SELECT count(*) FROM job_probe WHERE ended_at IS NULL"))
                .as("the job was interrupted before it could record its end")
                .isEqualTo(1);
        // The point of interrupting rather than abandoning: the job unwinds, so its lock is
        // released now instead of staying held (and the database pool is not closed under it).
        assertThat(
                        count(
                                "SELECT count(*) FROM shedlock WHERE name = 'probe-job'"
                                        + " AND lock_until <= timezone('utc', now())"))
                .as("the interrupted job released its lock")
                .isEqualTo(1);
        // Guard for the setting that makes the above true (application.yml explains why).
        assertThat(
                        app.getEnvironment()
                                .getProperty("spring.task.scheduling.shutdown.await-termination"))
                .isEqualTo("false");
    }

    @Test
    void aRunInFlightWhenTheInstanceShutsDownFinishesAndReleasesItsLock() throws Exception {
        ConfigurableApplicationContext app =
                startInstance("A", "--probe.work-millis=4000", "--probe.every=PT30S");
        waitForRunInFlight(app);

        app.close(); // shutdown while the 4 s run is mid-flight

        assertThat(count("SELECT count(*) FROM job_probe WHERE ended_at IS NOT NULL"))
                .as("the run was allowed to finish, not interrupted")
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM job_probe WHERE ended_at IS NULL")).isZero();
        assertThat(
                        count(
                                "SELECT count(*) FROM shedlock WHERE name = 'probe-job'"
                                        + " AND lock_until <= timezone('utc', now())"))
                .as("the lock was released, not left held until lockAtMostFor")
                .isEqualTo(1);
    }

    @Test
    void aLockLeftByADeadInstanceBlocksTheJobOnlyUntilItExpires() throws Exception {
        // A first instance creates the tables and runs the job once, then goes away.
        ConfigurableApplicationContext first = startInstance("A", "--probe.every=PT30S");
        waitUntil("the first run", 30_000, () -> atLeastRuns(1));
        first.close();
        try (Connection c = connect();
                Statement s = c.createStatement()) {
            s.execute("DELETE FROM job_probe");
            // What an instance killed mid-run leaves behind: a lock that is still held, for an
            // hour.
            // (Held until the test says otherwise, so this does not depend on how long startup
            // takes.)
            s.execute(
                    "UPDATE shedlock SET lock_until = timezone('utc', now()) + interval '1 hour',"
                            + " locked_by = 'dead-instance' WHERE name = 'probe-job'");
        }

        ConfigurableApplicationContext survivor = startInstance("B");
        try {
            Thread.sleep(3000); // ~15 attempts at the 200 ms schedule
            assertThat(count("SELECT count(*) FROM job_probe"))
                    .as("blocked while the dead instance's lock is still live")
                    .isZero();

            // The lock reaches its lockAtMostFor: the same effect as an hour having passed.
            try (Connection c = connect();
                    Statement s = c.createStatement()) {
                s.execute(
                        "UPDATE shedlock SET lock_until = timezone('utc', now()) - interval '1"
                                + " second' WHERE name = 'probe-job'");
            }
            waitUntil("the job to run once the lock expired", 30_000, () -> atLeastRuns(1));
        } finally {
            survivor.close();
        }

        assertThat(count("SELECT count(*) FROM job_probe WHERE instance = 'B'")).isPositive();
    }
}
