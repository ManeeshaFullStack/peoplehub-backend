package com.peoplehub.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.SqlErrors;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V9 (employee): applies cleanly, the table has the approved shape, and its constraints reject bad
 * data (b2-1, Spec 3.2, 12, 12.1). Mirrors {@code OrganizationMigrationTest}'s coverage of the
 * equivalent V8 migration. Runs as the container's superuser; {@code EmployeeRuntimeRoleTest}
 * covers what the runtime role may do.
 *
 * <p>No {@code @BeforeEach} cleanup: {@code organization} is referenced by the append-only {@code
 * audit_log} (b2-1, V12), so a table-wide {@code DELETE} can fail on a completely unrelated test
 * class's row with no way to clear it first. Every test creates its own fresh organization/employee
 * rows with unique keys instead (the same pattern {@code OrganizationMigrationTest} uses).
 */
@IntegrationTest
class EmployeeMigrationTest {

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

    private static String uniqueEmail() {
        return "jane-" + UUID.randomUUID() + "@example.com";
    }

    private static String uniqueCode() {
        return "E-" + UUID.randomUUID();
    }

    private UUID insertEmployee(UUID org) {
        String email = uniqueEmail();
        return jdbc.queryForObject(
                "INSERT INTO employee (organization_id, employee_code, name, email,"
                        + " email_normalized, role) VALUES (?, ?, 'Jane Doe', ?, ?, 'EMPLOYEE')"
                        + " RETURNING id",
                UUID.class,
                org,
                uniqueCode(),
                email,
                email);
    }

    @Test
    void v9AppliesCleanly() {
        Integer applied =
                jdbc.queryForObject(
                        "SELECT count(*) FROM flyway_schema_history WHERE version = '9' AND success",
                        Integer.class);
        Integer failed =
                jdbc.queryForObject(
                        "SELECT count(*) FROM flyway_schema_history WHERE NOT success",
                        Integer.class);

        assertThat(applied).isEqualTo(1);
        assertThat(failed).isZero();
    }

    @Test
    void columnsHaveTheApprovedTypesAndNullability() {
        record Column(String type, Integer length, String nullable) {}
        Map<String, Column> columns =
                jdbc
                        .queryForList(
                                "SELECT column_name, data_type, character_maximum_length,"
                                        + " is_nullable FROM information_schema.columns"
                                        + " WHERE table_name = 'employee'")
                        .stream()
                        .collect(
                                Collectors.toMap(
                                        r -> (String) r.get("column_name"),
                                        r ->
                                                new Column(
                                                        (String) r.get("data_type"),
                                                        (Integer) r.get("character_maximum_length"),
                                                        (String) r.get("is_nullable"))));

        assertThat(columns.keySet())
                .containsExactlyInAnyOrder(
                        "id",
                        "organization_id",
                        "department_id",
                        "employee_code",
                        "name",
                        "email",
                        "email_normalized",
                        "password_hash",
                        "status",
                        "role",
                        "default_approver_id",
                        "mfa_enabled",
                        "mfa_totp_secret",
                        "join_date",
                        "exit_date",
                        "welcome_seen_at",
                        "created_at",
                        "updated_at");
        assertThat(columns.get("id")).isEqualTo(new Column("uuid", null, "NO"));
        assertThat(columns.get("organization_id")).isEqualTo(new Column("uuid", null, "NO"));
        assertThat(columns.get("department_id")).isEqualTo(new Column("uuid", null, "YES"));
        assertThat(columns.get("employee_code"))
                .isEqualTo(new Column("character varying", 64, "NO"));
        assertThat(columns.get("mfa_totp_secret"))
                .isEqualTo(new Column("character varying", 512, "YES"));
        assertThat(columns.get("password_hash"))
                .isEqualTo(new Column("character varying", 255, "YES"));
        assertThat(columns.get("mfa_enabled")).isEqualTo(new Column("boolean", null, "NO"));
        assertThat(columns.get("join_date")).isEqualTo(new Column("date", null, "YES"));
        assertThat(columns.get("exit_date")).isEqualTo(new Column("date", null, "YES"));
    }

    @Test
    void organizationIdMustReferenceARealOrganization() {
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO employee (organization_id, employee_code,"
                                                + " name, email, email_normalized, role)"
                                                + " VALUES (?, ?, 'Jane Doe', ?, ?, 'EMPLOYEE')",
                                        UUID.randomUUID(),
                                        uniqueCode(),
                                        uniqueEmail(),
                                        uniqueEmail()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(SqlErrors.sqlState(e)).isEqualTo("23503"));
    }

    @Test
    void theNilOrganizationUuidIsRejectedByTheForeignKeyNotANilCheck() {
        // Unlike audit_log/email_outbox (no FK yet, so they needed an explicit nil-UUID CHECK),
        // employee's real FK to organization already rejects a nil UUID, since organization.id can
        // never legitimately be nil.
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO employee (organization_id, employee_code,"
                                                + " name, email, email_normalized, role)"
                                                + " VALUES (?, ?, 'Jane Doe', ?, ?, 'EMPLOYEE')",
                                        new UUID(0L, 0L),
                                        uniqueCode(),
                                        uniqueEmail(),
                                        uniqueEmail()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(SqlErrors.sqlState(e)).isEqualTo("23503"));
    }

    @Test
    void emailIsUniquePerOrganizationNotGlobally() {
        UUID org1 = insertOrganization();
        UUID org2 = insertOrganization();
        String email = uniqueEmail();
        jdbc.update(
                "INSERT INTO employee (organization_id, employee_code, name, email,"
                        + " email_normalized, role) VALUES (?, ?, 'Jane Doe', ?, ?, 'EMPLOYEE')",
                org1,
                uniqueCode(),
                email,
                email);

        // Same email, same org: rejected.
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO employee (organization_id, employee_code,"
                                                + " name, email, email_normalized, role)"
                                                + " VALUES (?, ?, 'Someone Else', ?, ?, 'EMPLOYEE')",
                                        org1,
                                        uniqueCode(),
                                        email,
                                        email))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(SqlErrors.sqlState(e)).isEqualTo("23505"));

        // Same email, different org: allowed (tenant-scoped uniqueness, Spec 12.1).
        jdbc.update(
                "INSERT INTO employee (organization_id, employee_code, name, email,"
                        + " email_normalized, role) VALUES (?, ?, 'Jane In Org 2', ?, ?,"
                        + " 'EMPLOYEE')",
                org2,
                uniqueCode(),
                email,
                email);
    }

    @Test
    void employeeCodeIsUniquePerOrganizationNotGlobally() {
        UUID org1 = insertOrganization();
        UUID org2 = insertOrganization();
        String code = uniqueCode();
        jdbc.update(
                "INSERT INTO employee (organization_id, employee_code, name, email,"
                        + " email_normalized, role) VALUES (?, ?, 'Jane Doe', ?, ?, 'EMPLOYEE')",
                org1,
                code,
                uniqueEmail(),
                uniqueEmail());

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO employee (organization_id, employee_code,"
                                                + " name, email, email_normalized, role)"
                                                + " VALUES (?, ?, 'Someone Else', ?, ?, 'EMPLOYEE')",
                                        org1,
                                        code,
                                        uniqueEmail(),
                                        uniqueEmail()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(SqlErrors.sqlState(e)).isEqualTo("23505"));

        jdbc.update(
                "INSERT INTO employee (organization_id, employee_code, name, email,"
                        + " email_normalized, role) VALUES (?, ?, 'Jane In Org 2', ?, ?,"
                        + " 'EMPLOYEE')",
                org2,
                code,
                uniqueEmail(),
                uniqueEmail());
    }

    @Test
    void employeeCodeCannotBeBlank() {
        UUID org = insertOrganization();

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO employee (organization_id, employee_code,"
                                                + " name, email, email_normalized, role)"
                                                + " VALUES (?, '', 'Jane Doe', ?, ?, 'EMPLOYEE')",
                                        org,
                                        uniqueEmail(),
                                        uniqueEmail()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .message()
                .contains("ck_employee_employee_code_not_blank");
    }

    @Test
    void nameCannotBeBlank() {
        UUID org = insertOrganization();

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO employee (organization_id, employee_code,"
                                                + " name, email, email_normalized, role)"
                                                + " VALUES (?, ?, '', ?, ?, 'EMPLOYEE')",
                                        org,
                                        uniqueCode(),
                                        uniqueEmail(),
                                        uniqueEmail()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .message()
                .contains("ck_employee_name_not_blank");
    }

    @Test
    void emailNormalizedMustAlreadyBeLowerCaseAndTrimmed() {
        UUID org = insertOrganization();

        for (String bad :
                new String[] {"Jane@Example.com", "jane@example.com ", " jane@example.com"}) {
            assertThatThrownBy(
                            () ->
                                    jdbc.update(
                                            "INSERT INTO employee (organization_id,"
                                                    + " employee_code, name, email,"
                                                    + " email_normalized, role) VALUES (?, ?,"
                                                    + " 'Jane Doe', ?, ?, 'EMPLOYEE')",
                                            org,
                                            uniqueCode(),
                                            "jane@example.com",
                                            bad))
                    .as("email_normalized = '" + bad + "'")
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .message()
                    .contains("ck_employee_email_normalized_is_normalized");
        }
    }

    @Test
    void statusAcceptsExactlyTheFiveApprovedValues() {
        UUID org = insertOrganization();
        for (String status :
                new String[] {
                    "PENDING_VERIFICATION", "INVITED", "ACTIVE", "DEACTIVATED", "DELETED_TOMBSTONE"
                }) {
            jdbc.update(
                    "INSERT INTO employee (organization_id, employee_code, name, email,"
                            + " email_normalized, role, status) VALUES (?, ?, 'Jane Doe', ?, ?,"
                            + " 'EMPLOYEE', ?)",
                    org,
                    uniqueCode(),
                    uniqueEmail(),
                    uniqueEmail(),
                    status);
        }

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO employee (organization_id, employee_code,"
                                                + " name, email, email_normalized, role, status)"
                                                + " VALUES (?, ?, 'Jane Doe', ?, ?, 'EMPLOYEE',"
                                                + " 'BOGUS')",
                                        org,
                                        uniqueCode(),
                                        uniqueEmail(),
                                        uniqueEmail()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .message()
                .contains("ck_employee_status");
    }

    @Test
    void statusDefaultsToInvited() {
        UUID org = insertOrganization();
        UUID id = insertEmployee(org);

        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM employee WHERE id = ?", String.class, id))
                .isEqualTo("INVITED");
    }

    @Test
    void roleMustBeOneOfTheThreeApprovedValues() {
        UUID org = insertOrganization();

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO employee (organization_id, employee_code,"
                                                + " name, email, email_normalized, role)"
                                                + " VALUES (?, ?, 'Jane Doe', ?, ?, 'BOGUS')",
                                        org,
                                        uniqueCode(),
                                        uniqueEmail(),
                                        uniqueEmail()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .message()
                .contains("ck_employee_role");
    }

    @Test
    void defaultApproverIdMustReferenceARealEmployeeWhenSupplied() {
        UUID org = insertOrganization();

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO employee (organization_id, employee_code,"
                                                + " name, email, email_normalized, role,"
                                                + " default_approver_id) VALUES (?, ?, 'Jane Doe',"
                                                + " ?, ?, 'EMPLOYEE', ?)",
                                        org,
                                        uniqueCode(),
                                        uniqueEmail(),
                                        uniqueEmail(),
                                        UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(SqlErrors.sqlState(e)).isEqualTo("23503"));
    }

    @Test
    void defaultApproverIdCanReferenceAnotherEmployeeInTheSameOrganization() {
        UUID org = insertOrganization();
        UUID approver = insertEmployee(org);

        jdbc.update(
                "INSERT INTO employee (organization_id, employee_code, name, email,"
                        + " email_normalized, role, default_approver_id) VALUES (?, ?, 'Jane Doe',"
                        + " ?, ?, 'EMPLOYEE', ?)",
                org,
                uniqueCode(),
                uniqueEmail(),
                uniqueEmail(),
                approver);
    }

    @Test
    void mfaEnabledDefaultsToFalse() {
        UUID org = insertOrganization();
        UUID id = insertEmployee(org);

        assertThat(
                        jdbc.queryForObject(
                                "SELECT mfa_enabled FROM employee WHERE id = ?", Boolean.class, id))
                .isFalse();
    }

    @Test
    void theRowIdIsAcceptedFromThisSuperuserConnectionButTheGeneratedDefaultAlsoWorks() {
        // Same reasoning as OrganizationMigrationTest: id's DEFAULT gen_random_uuid() is not
        // syntax-enforced; EmployeeRuntimeRoleTest proves the runtime role has no INSERT on id.
        UUID org = insertOrganization();
        UUID id1 = insertEmployee(org);
        UUID id2 = insertEmployee(org);

        assertThat(id1).isNotNull().isNotEqualTo(id2);
    }

    @Test
    void createdAtAndUpdatedAtDefaultToTheDatabaseClock() {
        UUID org = insertOrganization();
        UUID id = insertEmployee(org);

        Map<String, Object> row =
                jdbc.queryForMap(
                        "SELECT created_at <= now() AS created_timed,"
                                + " updated_at <= now() AS updated_timed FROM employee"
                                + " WHERE id = ?",
                        id);

        assertThat(row.get("created_timed")).isEqualTo(true);
        assertThat(row.get("updated_timed")).isEqualTo(true);
    }

    @Test
    void thereIsNoSecondaryIndexBeyondThePrimaryKeyAndTheTenantScopedUniques() {
        var indexes =
                jdbc.queryForList(
                        "SELECT indexname FROM pg_indexes WHERE tablename = 'employee'",
                        String.class);

        // uq_employee_organization_id is V14's (b2-3): the target of refresh_token's composite
        // tenant-safe foreign key.
        assertThat(indexes)
                .containsExactlyInAnyOrder(
                        "pk_employee",
                        "uq_employee_organization_email",
                        "uq_employee_organization_code",
                        "uq_employee_organization_id");
    }
}
