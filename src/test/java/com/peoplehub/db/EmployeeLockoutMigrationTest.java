package com.peoplehub.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.PrivilegedFixture;
import com.peoplehub.support.SqlErrors;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V16 (b2-5, B2-5/P3, P13): per-account lockout columns on {@code employee}, and the two new
 * refresh-token revoke reasons. Runs as the container's superuser; {@code
 * EmployeeLockoutRuntimeRoleTest} covers what the runtime role may do. Every test creates its own
 * rows with unique values.
 */
@IntegrationTest
class EmployeeLockoutMigrationTest {

    @Autowired @PrivilegedFixture private JdbcTemplate jdbc;

    private UUID insertOrganization() {
        return jdbc.queryForObject(
                "INSERT INTO organization (name, login_key_normalized, timezone) VALUES (?, ?, ?)"
                        + " RETURNING id",
                UUID.class,
                "Acme Corp",
                "org-" + UUID.randomUUID(),
                "Asia/Kolkata");
    }

    private UUID insertEmployee(UUID org) {
        String email = "jane-" + UUID.randomUUID() + "@example.com";
        return jdbc.queryForObject(
                "INSERT INTO employee (organization_id, employee_code, name, email,"
                        + " email_normalized, role) VALUES (?, ?, 'Jane Doe', ?, ?, 'EMPLOYEE')"
                        + " RETURNING id",
                UUID.class,
                org,
                "E-" + UUID.randomUUID(),
                email,
                email);
    }

    @Test
    void v16AppliesCleanly() {
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM flyway_schema_history WHERE version = '16'"
                                        + " AND success",
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void theLockoutColumnsHaveTheApprovedTypesAndNullability() {
        Map<String, Object> failedCount =
                jdbc.queryForMap(
                        "SELECT data_type, is_nullable, column_default FROM"
                                + " information_schema.columns WHERE table_name = 'employee'"
                                + " AND column_name = 'failed_login_count'");
        Map<String, Object> lockedUntil =
                jdbc.queryForMap(
                        "SELECT data_type, is_nullable FROM information_schema.columns"
                                + " WHERE table_name = 'employee' AND column_name = 'locked_until'");

        assertThat(failedCount)
                .containsEntry("data_type", "integer")
                .containsEntry("is_nullable", "NO")
                .containsEntry("column_default", "0");
        assertThat(lockedUntil)
                .containsEntry("data_type", "timestamp with time zone")
                .containsEntry("is_nullable", "YES");
    }

    @Test
    void aNewEmployeeHasNoFailuresAndNoLock() {
        UUID employee = insertEmployee(insertOrganization());

        Map<String, Object> row =
                jdbc.queryForMap(
                        "SELECT failed_login_count, locked_until FROM employee WHERE id = ?",
                        employee);

        assertThat(row.get("failed_login_count")).isEqualTo(0);
        assertThat(row.get("locked_until")).isNull();
    }

    @Test
    void theFailureCountCanNeverBeNegative() {
        UUID employee = insertEmployee(insertOrganization());

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE employee SET failed_login_count = -1 WHERE id = ?",
                                        employee))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(
                        e -> assertThat(SqlErrors.sqlState(e)).isEqualTo(SqlErrors.CHECK_VIOLATION))
                .message()
                .contains("ck_employee_failed_login_count_not_negative");
    }

    @Test
    void aLockCanBeSetAndCleared() {
        UUID employee = insertEmployee(insertOrganization());
        Timestamp until = Timestamp.from(Instant.now().plus(1, ChronoUnit.MINUTES));

        jdbc.update(
                "UPDATE employee SET failed_login_count = 5, locked_until = ? WHERE id = ?",
                until,
                employee);
        jdbc.update(
                "UPDATE employee SET failed_login_count = 0, locked_until = NULL WHERE id = ?",
                employee);

        assertThat(
                        jdbc.queryForObject(
                                "SELECT locked_until FROM employee WHERE id = ?",
                                Timestamp.class,
                                employee))
                .isNull();
    }

    @Test
    void theRevokeReasonsIncludePasswordResetAndPasswordChangedButNothingElseNew() {
        String definition =
                jdbc.queryForObject(
                        "SELECT pg_get_constraintdef(oid) FROM pg_constraint"
                                + " WHERE conname = 'ck_refresh_token_revoke_reason'",
                        String.class);

        assertThat(definition)
                .contains("ROTATED")
                .contains("LOGOUT")
                .contains("REUSE_DETECTED")
                .contains("PASSWORD_RESET")
                .contains("PASSWORD_CHANGED")
                .doesNotContain("EXPIRED");
    }
}
