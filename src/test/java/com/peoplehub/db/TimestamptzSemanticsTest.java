package com.peoplehub.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.peoplehub.support.IntegrationTest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Guards the database assumptions the attendance model depends on (Spec 4.2): instants are stored
 * as {@code timestamptz} and never shift with a session timezone, and {@code btree_gist} can reject
 * overlapping sessions. Uses temp tables inside a rolled-back transaction, so nothing persists.
 */
@IntegrationTest
@Transactional
class TimestamptzSemanticsTest {

    // One millisecond before US clocks spring forward (2026-03-08 07:00Z): the classic place where
    // wall-clock arithmetic goes wrong.
    private static final Instant NEAR_DST_CHANGE = Instant.parse("2026-03-08T06:59:59.999Z");

    // The application's connection, as the runtime role: the SQL runs inside the test-managed
    // transaction, which is rolled back, so the temporary tables never outlive a test.
    @Autowired private JdbcTemplate jdbc;

    @ParameterizedTest
    @ValueSource(strings = {"UTC", "Asia/Kolkata", "America/New_York"})
    void instantRoundTripsUnchangedRegardlessOfSessionTimeZone(String sessionTimeZone) {
        jdbc.execute("SET LOCAL TIME ZONE '" + sessionTimeZone + "'");
        jdbc.execute("CREATE TEMP TABLE ts_probe (at timestamptz NOT NULL)");

        jdbc.update(
                "INSERT INTO ts_probe (at) VALUES (?)",
                OffsetDateTime.ofInstant(NEAR_DST_CHANGE, ZoneOffset.UTC));
        OffsetDateTime stored =
                jdbc.queryForObject("SELECT at FROM ts_probe", OffsetDateTime.class);

        assertThat(stored).isNotNull();
        assertThat(stored.toInstant()).isEqualTo(NEAR_DST_CHANGE);
    }

    @Test
    void exclusionConstraintRejectsOverlappingRangesButAllowsBackToBack() {
        jdbc.execute(
                """
                CREATE TEMP TABLE session_probe (
                    employee_id  bigint      NOT NULL,
                    check_in_at  timestamptz NOT NULL,
                    check_out_at timestamptz,
                    EXCLUDE USING gist (
                        employee_id WITH =,
                        tstzrange(check_in_at, check_out_at) WITH &&)
                )
                """);
        String insert = "INSERT INTO session_probe VALUES (?, ?::timestamptz, ?::timestamptz)";

        jdbc.update(insert, 1L, "2026-03-02T09:00:00Z", "2026-03-02T12:00:00Z");
        // Half-open ranges: a session starting exactly when the previous one ends is fine.
        jdbc.update(insert, 1L, "2026-03-02T12:00:00Z", "2026-03-02T13:00:00Z");
        // A different employee may overlap in time.
        jdbc.update(insert, 2L, "2026-03-02T09:30:00Z", "2026-03-02T10:30:00Z");

        // Must be last: a violation aborts the surrounding transaction.
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        insert, 1L, "2026-03-02T11:00:00Z", "2026-03-02T11:30:00Z"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
