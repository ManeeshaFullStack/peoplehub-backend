package com.peoplehub.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.PrivilegedFixture;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** V2 (ShedLock lock table): applies cleanly and the constraints actually reject bad data. */
@IntegrationTest
class ShedLockTableMigrationTest {

    private static final String INSERT =
            "INSERT INTO shedlock(name, lock_until, locked_at, locked_by) VALUES (?, "
                    + "timezone('utc', now()), timezone('utc', now()), 'test')";

    @Autowired @PrivilegedFixture private JdbcTemplate jdbc;

    @Test
    void v2AppliesCleanly() {
        Integer applied =
                jdbc.queryForObject(
                        "SELECT count(*) FROM flyway_schema_history WHERE version = '2' AND success",
                        Integer.class);

        assertThat(applied).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM flyway_schema_history WHERE NOT success",
                                Integer.class))
                .isZero();
    }

    @Test
    void tableHasTheColumnsShedLockExpects() {
        Map<String, String> types =
                jdbc
                        .queryForList(
                                "SELECT column_name, data_type FROM information_schema.columns"
                                        + " WHERE table_name = 'shedlock'")
                        .stream()
                        .collect(
                                Collectors.toMap(
                                        r -> (String) r.get("column_name"),
                                        r -> (String) r.get("data_type")));

        assertThat(types)
                .containsEntry("name", "character varying")
                .containsEntry("locked_by", "character varying")
                // Plain timestamps on purpose: timestamptz makes lock expiry depend on the session
                // time zone (see the V2 header and ShedLockTimeZoneTest).
                .containsEntry("lock_until", "timestamp without time zone")
                .containsEntry("locked_at", "timestamp without time zone")
                .hasSize(4);
    }

    @Test
    void aLockNameCanOnlyExistOnce() {
        String name = "migration-test-" + System.nanoTime();
        jdbc.update(INSERT, name);

        assertThatThrownBy(() -> jdbc.update(INSERT, name))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void everyColumnIsRequired() {
        List<String> nullable =
                jdbc.queryForList(
                        "SELECT column_name FROM information_schema.columns"
                                + " WHERE table_name = 'shedlock' AND is_nullable = 'YES'",
                        String.class);

        assertThat(nullable).isEmpty();
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO shedlock(name, lock_until, locked_at,"
                                                + " locked_by) VALUES ('null-check', NULL,"
                                                + " timezone('utc', now()), 'test')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
