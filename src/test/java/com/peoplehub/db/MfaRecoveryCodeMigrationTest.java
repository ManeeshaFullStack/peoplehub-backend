package com.peoplehub.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.PrivilegedFixture;
import com.peoplehub.support.SqlErrors;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V11 (mfa_recovery_code): applies cleanly, the table has the approved shape, and its constraints
 * reject bad data (b2-1, Spec 8.3, 12). Mirrors {@code RefreshTokenMigrationTest}'s coverage of the
 * equivalent table. Runs as the container's superuser; {@code MfaRecoveryCodeRuntimeRoleTest}
 * covers what the runtime role may do.
 *
 * <p>No {@code @BeforeEach} cleanup: {@code organization} is referenced by the append-only {@code
 * audit_log} (b2-1, V12), so a table-wide {@code DELETE} can fail on an unrelated test class's row.
 * Every test creates its own fresh organization/employee/code rows instead.
 */
@IntegrationTest
class MfaRecoveryCodeMigrationTest {

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

    // Since V20 every insert names organization_id, which is NOT NULL
    // (MfaRecoveryCodeTenantMigrationTest).
    private static final String INSERT =
            "INSERT INTO mfa_recovery_code (organization_id, employee_id, code_hash) VALUES (?, ?, ?)";

    @Test
    void employeeIdMustReferenceARealEmployee() {
        UUID org = insertOrganization();

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        INSERT,
                                        org,
                                        UUID.randomUUID(),
                                        "hash-" + UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(SqlErrors.sqlState(e)).isEqualTo("23503"));
    }

    @Test
    void codeHashMustBeUnique() {
        UUID org = insertOrganization();
        UUID employee = insertEmployee(org);
        String hash = "hash-" + UUID.randomUUID();
        jdbc.update(INSERT, org, employee, hash);

        assertThatThrownBy(() -> jdbc.update(INSERT, org, employee, hash))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(SqlErrors.sqlState(e)).isEqualTo("23505"));
    }

    @Test
    void codeHashCannotBeBlank() {
        UUID org = insertOrganization();
        UUID employee = insertEmployee(org);

        assertThatThrownBy(() -> jdbc.update(INSERT, org, employee, ""))
                .isInstanceOf(DataIntegrityViolationException.class)
                .message()
                .contains("ck_mfa_recovery_code_code_hash_not_blank");
    }

    @Test
    void usedAtIsNullUntilExplicitlySet() {
        UUID org = insertOrganization();
        UUID employee = insertEmployee(org);
        Long id =
                jdbc.queryForObject(
                        INSERT + " RETURNING id",
                        Long.class,
                        org,
                        employee,
                        "hash-" + UUID.randomUUID());

        assertThat(
                        jdbc.queryForObject(
                                "SELECT used_at FROM mfa_recovery_code WHERE id = ?",
                                java.sql.Timestamp.class,
                                id))
                .isNull();
    }

    @Test
    void theRowIdIsAlwaysGeneratedNeverSupplied() {
        UUID org = insertOrganization();
        UUID employee = insertEmployee(org);

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO mfa_recovery_code (id, organization_id,"
                                                + " employee_id, code_hash) VALUES (1, ?, ?, ?)",
                                        org,
                                        employee,
                                        "hash-" + UUID.randomUUID()))
                .satisfies(e -> assertThat(SqlErrors.sqlMessage(e)).contains("GENERATED ALWAYS"));
    }

    @Test
    void indexesExist() {
        var indexes =
                jdbc.queryForList(
                        "SELECT indexname FROM pg_indexes WHERE tablename = 'mfa_recovery_code'",
                        String.class);

        assertThat(indexes)
                .containsExactlyInAnyOrder(
                        "pk_mfa_recovery_code",
                        "uq_mfa_recovery_code_code_hash",
                        "idx_mfa_recovery_code_employee_used");
    }
}
