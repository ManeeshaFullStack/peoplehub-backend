package com.peoplehub.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.SqlErrors;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V11 (refresh_token): applies cleanly, the table has the approved shape, and its constraints
 * reject bad data (b2-1, Spec 8.1, 12). Mirrors {@code EmployeeInvitationMigrationTest}'s coverage
 * of the equivalent V10 migration. Runs as the container's superuser; {@code
 * RefreshTokenRuntimeRoleTest} covers what the runtime role may do. Inserts supply {@code
 * organization_id}, required since V14 ({@code RefreshTokenRotationMigrationTest}).
 *
 * <p>No {@code @BeforeEach} cleanup: {@code organization} is referenced by the append-only {@code
 * audit_log} (b2-1, V12), so a table-wide {@code DELETE} can fail on an unrelated test class's row.
 * Every test creates its own fresh organization/employee/token rows instead.
 */
@IntegrationTest
class RefreshTokenMigrationTest {

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

    private static Timestamp in(int amount, ChronoUnit unit) {
        return Timestamp.from(Instant.now().plus(amount, unit));
    }

    @Test
    void v11AppliesCleanly() {
        Integer applied =
                jdbc.queryForObject(
                        "SELECT count(*) FROM flyway_schema_history WHERE version = '11' AND"
                                + " success",
                        Integer.class);
        Integer failed =
                jdbc.queryForObject(
                        "SELECT count(*) FROM flyway_schema_history WHERE NOT success",
                        Integer.class);

        assertThat(applied).isEqualTo(1);
        assertThat(failed).isZero();
    }

    @Test
    void employeeIdMustReferenceARealEmployee() {
        UUID org = insertOrganization();
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO refresh_token (organization_id, employee_id,"
                                                + " token_hash, family_id, expires_at,"
                                                + " absolute_expires_at) VALUES (?, ?, ?, ?, ?, ?)",
                                        org,
                                        UUID.randomUUID(),
                                        "hash-" + UUID.randomUUID(),
                                        UUID.randomUUID(),
                                        in(30, ChronoUnit.DAYS),
                                        in(90, ChronoUnit.DAYS)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(SqlErrors.sqlState(e)).isEqualTo("23503"));
    }

    @Test
    void tokenHashMustBeUnique() {
        UUID org = insertOrganization();
        UUID employee = insertEmployee(org);
        String hash = "hash-" + UUID.randomUUID();
        jdbc.update(
                "INSERT INTO refresh_token (organization_id, employee_id, token_hash, family_id,"
                        + " expires_at, absolute_expires_at) VALUES (?, ?, ?, ?, ?, ?)",
                org,
                employee,
                hash,
                UUID.randomUUID(),
                in(30, ChronoUnit.DAYS),
                in(90, ChronoUnit.DAYS));

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO refresh_token (organization_id, employee_id,"
                                                + " token_hash, family_id, expires_at,"
                                                + " absolute_expires_at) VALUES (?, ?, ?, ?, ?, ?)",
                                        org,
                                        employee,
                                        hash,
                                        UUID.randomUUID(),
                                        in(30, ChronoUnit.DAYS),
                                        in(90, ChronoUnit.DAYS)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(SqlErrors.sqlState(e)).isEqualTo("23505"));
    }

    @Test
    void tokenHashCannotBeBlank() {
        UUID org = insertOrganization();
        UUID employee = insertEmployee(org);

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO refresh_token (organization_id, employee_id,"
                                                + " token_hash, family_id, expires_at,"
                                                + " absolute_expires_at) VALUES (?, ?, '', ?, ?, ?)",
                                        org,
                                        employee,
                                        UUID.randomUUID(),
                                        in(30, ChronoUnit.DAYS),
                                        in(90, ChronoUnit.DAYS)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .message()
                .contains("ck_refresh_token_token_hash_not_blank");
    }

    @Test
    void revokedDefaultsToFalse() {
        UUID org = insertOrganization();
        UUID employee = insertEmployee(org);
        UUID id =
                jdbc.queryForObject(
                        "INSERT INTO refresh_token (organization_id, employee_id, token_hash,"
                                + " family_id, expires_at, absolute_expires_at)"
                                + " VALUES (?, ?, ?, ?, ?, ?) RETURNING id",
                        UUID.class,
                        org,
                        employee,
                        "hash-" + UUID.randomUUID(),
                        UUID.randomUUID(),
                        in(30, ChronoUnit.DAYS),
                        in(90, ChronoUnit.DAYS));

        assertThat(
                        jdbc.queryForObject(
                                "SELECT revoked FROM refresh_token WHERE id = ?",
                                Boolean.class,
                                id))
                .isFalse();
    }

    @Test
    void theRowIndexesExist() {
        var indexes =
                jdbc.queryForList(
                        "SELECT indexname FROM pg_indexes WHERE tablename = 'refresh_token'",
                        String.class);

        assertThat(indexes)
                .containsExactlyInAnyOrder(
                        "pk_refresh_token",
                        "uq_refresh_token_token_hash",
                        "idx_refresh_token_family",
                        "idx_refresh_token_employee_revoked");
    }

    @Test
    void thereIsNoAppendOnlyTrigger() {
        Integer triggers =
                jdbc.queryForObject(
                        "SELECT count(*) FROM pg_trigger"
                                + " WHERE tgrelid = 'refresh_token'::regclass AND NOT tgisinternal",
                        Integer.class);

        assertThat(triggers).isZero();
    }
}
