package com.peoplehub.common.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import com.peoplehub.support.IntegrationTest;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Regression guard for the V2 column type. Two instances whose JVMs (and so database sessions) are
 * in different time zones must still agree on when a lock expires. With {@code timestamptz} columns
 * they do not: measured against real PostgreSQL, a live lock was stolen (double execution) or stuck
 * for hours in 5 of 6 zone pairs. If someone changes the columns to {@code timestamptz}, this test
 * fails.
 */
@IntegrationTest
class ShedLockTimeZoneTest {

    private static final List<String> ZONES = List.of("UTC", "Asia/Kolkata", "America/New_York");

    @Autowired private DataSource dataSource;

    private final List<HikariDataSource> opened = new ArrayList<>();

    @AfterEach
    void closeSessions() {
        opened.forEach(HikariDataSource::close);
    }

    /**
     * A connection pool whose sessions are in the given time zone, like an instance in that JVM.
     */
    private LockProvider instanceInZone(String zone) throws Exception {
        HikariDataSource base = dataSource.unwrap(HikariDataSource.class);
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(base.getJdbcUrl());
        config.setUsername(base.getUsername());
        config.setPassword(base.getPassword());
        config.setMaximumPoolSize(2);
        config.setConnectionInitSql("SET TIME ZONE '" + zone + "'");
        HikariDataSource zoned = new HikariDataSource(config);
        opened.add(zoned);
        return SchedulingConfig.createLockProvider(zoned);
    }

    private static LockConfiguration lock(String name, Duration atMost) {
        return new LockConfiguration(Instant.now(), name, atMost, Duration.ZERO);
    }

    @Test
    void aLiveLockCannotBeTakenByAnInstanceInAnotherTimeZone() throws Exception {
        for (String holderZone : ZONES) {
            for (String contenderZone : ZONES) {
                if (holderZone.equals(contenderZone)) {
                    continue;
                }
                String name = "tz-" + UUID.randomUUID().toString().substring(0, 8);
                LockProvider holder = instanceInZone(holderZone);
                LockProvider contender = instanceInZone(contenderZone);

                SimpleLock held = holder.lock(lock(name, Duration.ofMinutes(5))).orElseThrow();
                Optional<SimpleLock> stolen = contender.lock(lock(name, Duration.ofMinutes(5)));

                assertThat(stolen)
                        .as("held in %s, contended from %s", holderZone, contenderZone)
                        .isEmpty();
                held.unlock();
            }
        }
    }

    @Test
    void theLockExpiresAtTheSameInstantWhicheverZoneTookIt() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        for (String zone : ZONES) {
            String name = "tz-expiry-" + UUID.randomUUID().toString().substring(0, 8);

            instanceInZone(zone).lock(lock(name, Duration.ofMinutes(5))).orElseThrow();

            // Seconds from now until the lock frees, measured on the database's clock. It must be
            // just under the 5 minutes requested, not hours off in either direction.
            Double secondsLeft =
                    jdbc.queryForObject(
                            "SELECT extract(epoch FROM lock_until - timezone('utc', now()))"
                                    + " FROM shedlock WHERE name = ?",
                            Double.class,
                            name);
            assertThat(secondsLeft).as("taken from %s", zone).isBetween(240.0, 300.0);
        }
    }
}
