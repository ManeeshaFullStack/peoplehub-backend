package com.peoplehub.common.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.PrivilegedFixture;
import com.peoplehub.support.SqlErrors;
import com.peoplehub.support.TestDatabaseRoles;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * ShedLock keeps working when the application connects as the least-privileged runtime role (B0-6):
 * V3 grants it SELECT, INSERT and UPDATE on {@code shedlock} and nothing else. The lock provider is
 * built through the production factory, over a data source that logs in as that role.
 *
 * <p>Every operation the lock uses is exercised (take a free lock, refuse a held one, release, take
 * again, take over an expired one). None needs DELETE, which the role does not have.
 */
@IntegrationTest
class ShedLockRuntimeRoleTest {

    @Autowired private PostgreSQLContainer postgres;
    @Autowired @PrivilegedFixture private JdbcTemplate superuserJdbc;
    @Autowired private Clock clock;

    private LockProvider provider;
    private DataSource runtimeDataSource;

    @BeforeEach
    void connectAsRuntimeRole() {
        runtimeDataSource = TestDatabaseRoles.runtimeDataSource(postgres);
        provider = SchedulingConfig.createLockProvider(runtimeDataSource);
    }

    private LockConfiguration lock(String name, Duration atMost) {
        return new LockConfiguration(clock.instant(), name, atMost, Duration.ZERO);
    }

    private static String uniqueName() {
        return "runtime-role-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void theProviderReallyConnectsAsTheRuntimeRole() throws SQLException {
        try (Connection c = runtimeDataSource.getConnection();
                Statement s = c.createStatement();
                var rs = s.executeQuery("SELECT current_user")) {
            rs.next();
            assertThat(rs.getString(1)).isEqualTo(TestDatabaseRoles.RUNTIME_ROLE);
        }
    }

    @Test
    void aFreeLockIsTakenAndAHeldLockIsRefused() {
        String name = uniqueName();

        Optional<SimpleLock> first = provider.lock(lock(name, Duration.ofMinutes(5)));
        Optional<SimpleLock> second = provider.lock(lock(name, Duration.ofMinutes(5)));

        assertThat(first).isPresent();
        assertThat(second).isEmpty();
        first.get().unlock();
    }

    @Test
    void aReleasedLockCanBeTakenAgain() {
        String name = uniqueName();

        SimpleLock held = provider.lock(lock(name, Duration.ofMinutes(5))).orElseThrow();
        held.unlock();
        Optional<SimpleLock> again = provider.lock(lock(name, Duration.ofMinutes(5)));

        assertThat(again).isPresent();
        again.get().unlock();
    }

    @Test
    void anExpiredLockIsTakenOver() {
        String name = uniqueName();
        provider.lock(lock(name, Duration.ofMinutes(5))).orElseThrow();
        // The holder "died": its lock ran out. (The superuser edits the row; the role could not.)
        superuserJdbc.update(
                "UPDATE shedlock SET lock_until = timezone('utc', now()) - interval '1 minute'"
                        + " WHERE name = ?",
                name);

        Optional<SimpleLock> takeover = provider.lock(lock(name, Duration.ofMinutes(5)));

        assertThat(takeover).isPresent();
        takeover.get().unlock();
    }

    @Test
    void lockRowsAreWrittenByTheRuntimeRole() {
        String name = uniqueName();
        provider.lock(lock(name, Duration.ofMinutes(5))).orElseThrow().unlock();

        Integer rows =
                superuserJdbc.queryForObject(
                        "SELECT count(*) FROM shedlock WHERE name = ?", Integer.class, name);

        assertThat(rows).isEqualTo(1);
    }

    @Test
    void theRuntimeRoleCannotDeleteOrTruncateLockRows() {
        assertThatThrownBy(
                        () -> {
                            try (Connection c = runtimeDataSource.getConnection();
                                    Statement s = c.createStatement()) {
                                s.execute("DELETE FROM shedlock");
                            }
                        })
                .satisfies(
                        e ->
                                assertThat(SqlErrors.sqlState(e))
                                        .isEqualTo(SqlErrors.INSUFFICIENT_PRIVILEGE));
        assertThatThrownBy(
                        () -> {
                            try (Connection c = runtimeDataSource.getConnection();
                                    Statement s = c.createStatement()) {
                                s.execute("TRUNCATE shedlock");
                            }
                        })
                .satisfies(
                        e ->
                                assertThat(SqlErrors.sqlState(e))
                                        .isEqualTo(SqlErrors.INSUFFICIENT_PRIVILEGE));
    }

    // ---- the grants are exactly what ShedLock needs: remove any one and it fails ----

    private static final String PROBE_ROLE = "shedlock_probe";

    /**
     * Take, refuse, release, retake and take over an expired lock: every operation ShedLock uses.
     */
    private void fullLockCycle(LockProvider provider, String name) {
        SimpleLock first = provider.lock(lock(name, Duration.ofMinutes(5))).orElseThrow();
        assertThat(provider.lock(lock(name, Duration.ofMinutes(5)))).isEmpty();
        first.unlock();
        provider.lock(lock(name, Duration.ofMinutes(5))).orElseThrow();
        superuserJdbc.update(
                "UPDATE shedlock SET lock_until = timezone('utc', now()) - interval '1 minute'"
                        + " WHERE name = ?",
                name);
        provider.lock(lock(name, Duration.ofMinutes(5))).orElseThrow().unlock();
    }

    private void withProbeRoleGranted(String privileges, Runnable useIt) {
        superuserJdbc.execute("CREATE ROLE " + PROBE_ROLE + " LOGIN PASSWORD 'probe'");
        try {
            superuserJdbc.execute("GRANT USAGE ON SCHEMA public TO " + PROBE_ROLE);
            superuserJdbc.execute("GRANT " + privileges + " ON shedlock TO " + PROBE_ROLE);
            useIt.run();
        } finally {
            superuserJdbc.execute("DROP OWNED BY " + PROBE_ROLE);
            superuserJdbc.execute("DROP ROLE " + PROBE_ROLE);
        }
    }

    private LockProvider probeProvider() {
        return SchedulingConfig.createLockProvider(
                new DriverManagerDataSource(postgres.getJdbcUrl(), PROBE_ROLE, "probe"));
    }

    @Test
    void selectInsertAndUpdateTogetherAreEnoughForShedLock() {
        // The control: exactly the three V3 grants, still no DELETE, and the whole cycle works.
        withProbeRoleGranted(
                "SELECT, INSERT, UPDATE", () -> fullLockCycle(probeProvider(), uniqueName()));
    }

    @Test
    void shedLockFailsWithoutSelectBecauseAnUpdateWithAWhereClauseReadsColumns() {
        withProbeRoleGranted(
                "INSERT, UPDATE",
                () ->
                        assertThatThrownBy(() -> fullLockCycle(probeProvider(), uniqueName()))
                                .satisfies(
                                        e ->
                                                assertThat(SqlErrors.sqlState(e))
                                                        .isEqualTo(
                                                                SqlErrors.INSUFFICIENT_PRIVILEGE)));
    }

    @Test
    void shedLockFailsWithoutInsertBecauseTheFirstLockOfANameIsAnInsert() {
        withProbeRoleGranted(
                "SELECT, UPDATE",
                () ->
                        assertThatThrownBy(() -> fullLockCycle(probeProvider(), uniqueName()))
                                .satisfies(
                                        e ->
                                                assertThat(SqlErrors.sqlState(e))
                                                        .isEqualTo(
                                                                SqlErrors.INSUFFICIENT_PRIVILEGE)));
    }

    @Test
    void shedLockFailsWithoutUpdateBecauseTakingAndReleasingALockIsAnUpdate() {
        withProbeRoleGranted(
                "SELECT, INSERT",
                () ->
                        assertThatThrownBy(() -> fullLockCycle(probeProvider(), uniqueName()))
                                .satisfies(
                                        e ->
                                                assertThat(SqlErrors.sqlState(e))
                                                        .isEqualTo(
                                                                SqlErrors.INSUFFICIENT_PRIVILEGE)));
    }
}
