package com.peoplehub.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.peoplehub.support.IntegrationTest;
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
 * V17 ({@code password_reset_token}, b2-5, B2-5/P6-P8): applies cleanly, has the approved shape,
 * and its constraints reject bad data. Runs as the container's superuser; {@code
 * PasswordResetTokenRuntimeRoleTest} covers what the runtime role may do. Every test creates its
 * own rows with unique values.
 */
@IntegrationTest
class PasswordResetTokenMigrationTest {

    private static final String INSERT =
            "INSERT INTO password_reset_token (organization_id, employee_id, token_hash,"
                    + " expires_at) VALUES (?, ?, ?, ?) RETURNING id";

    @Autowired private JdbcTemplate jdbc;

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

    private static Timestamp inThirtyMinutes() {
        return Timestamp.from(Instant.now().plus(30, ChronoUnit.MINUTES));
    }

    private UUID insertToken(UUID org, UUID employee, String hash) {
        return jdbc.queryForObject(INSERT, UUID.class, org, employee, hash, inThirtyMinutes());
    }

    private static void assertViolation(Runnable statement, String sqlState, String constraint) {
        assertThatThrownBy(statement::run)
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(SqlErrors.sqlState(e)).isEqualTo(sqlState))
                .message()
                .contains(constraint);
    }

    @Test
    void v17AppliesCleanly() {
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM flyway_schema_history WHERE version = '17'"
                                        + " AND success",
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void aNewTokenIsUnusedAndTimedByTheDatabase() {
        UUID org = insertOrganization();
        UUID id = insertToken(org, insertEmployee(org), "hash-" + UUID.randomUUID());

        Map<String, Object> row =
                jdbc.queryForMap(
                        "SELECT consumed_at, invalidated_at, created_at <= now() AS timed"
                                + " FROM password_reset_token WHERE id = ?",
                        id);
        assertThat(row.get("consumed_at")).isNull();
        assertThat(row.get("invalidated_at")).isNull();
        assertThat(row.get("timed")).isEqualTo(true);
    }

    @Test
    void aTokenCannotNameAnEmployeeOfAnotherOrganization() {
        UUID org = insertOrganization();
        UUID employeeOfAnother = insertEmployee(insertOrganization());

        assertViolation(
                () -> insertToken(org, employeeOfAnother, "hash-" + UUID.randomUUID()),
                "23503",
                "fk_password_reset_token_employee_in_organization");
    }

    @Test
    void aTokenHashBelongsToOneRowOnly() {
        UUID org = insertOrganization();
        UUID employee = insertEmployee(org);
        String hash = "hash-" + UUID.randomUUID();
        insertToken(org, employee, hash);

        assertViolation(
                () -> insertToken(org, employee, hash),
                "23505",
                "uq_password_reset_token_token_hash");
    }

    @Test
    void aBlankTokenHashIsRejected() {
        UUID org = insertOrganization();
        UUID employee = insertEmployee(org);

        assertViolation(
                () -> insertToken(org, employee, ""),
                SqlErrors.CHECK_VIOLATION,
                "ck_password_reset_token_token_hash_not_blank");
    }

    @Test
    void aTokenIsNeverBothConsumedAndInvalidated() {
        UUID org = insertOrganization();
        UUID employee = insertEmployee(org);
        UUID consumed = insertToken(org, employee, "hash-" + UUID.randomUUID());
        jdbc.update("UPDATE password_reset_token SET consumed_at = now() WHERE id = ?", consumed);

        assertViolation(
                () ->
                        jdbc.update(
                                "UPDATE password_reset_token SET invalidated_at = now()"
                                        + " WHERE id = ?",
                                consumed),
                SqlErrors.CHECK_VIOLATION,
                "ck_password_reset_token_not_consumed_and_invalidated");
    }

    @Test
    void requiredColumnsAreRequired() {
        UUID org = insertOrganization();
        UUID employee = insertEmployee(org);

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO password_reset_token (organization_id,"
                                                + " employee_id, token_hash) VALUES (?, ?, ?)",
                                        org,
                                        employee,
                                        "hash-" + UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(
                        e ->
                                assertThat(SqlErrors.sqlState(e))
                                        .isEqualTo(SqlErrors.NOT_NULL_VIOLATION));
    }

    @Test
    void theIndexesAreThePrimaryKeyTheUniqueHashAndTheThrottleIndex() {
        assertThat(
                        jdbc.queryForList(
                                "SELECT indexname FROM pg_indexes"
                                        + " WHERE tablename = 'password_reset_token'",
                                String.class))
                .containsExactlyInAnyOrder(
                        "pk_password_reset_token",
                        "uq_password_reset_token_token_hash",
                        "idx_password_reset_token_employee_created");
    }

    @Test
    void thereIsNoTrigger() {
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM pg_trigger WHERE tgrelid ="
                                        + " 'password_reset_token'::regclass AND NOT tgisinternal",
                                Integer.class))
                .isZero();
    }
}
