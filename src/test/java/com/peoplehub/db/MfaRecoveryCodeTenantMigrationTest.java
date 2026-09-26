package com.peoplehub.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.PrivilegedFixture;
import com.peoplehub.support.SqlErrors;
import com.peoplehub.support.TestDatabaseRoles;
import com.peoplehub.support.TestcontainersConfiguration;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * V20 (b2-7, B2-7/8, B2-7/12): recovery codes are bound to their organization and can be
 * invalidated, never deleted. Runs as the container's superuser; {@code
 * MfaRecoveryCodeTenantRuntimeRoleTest} covers the runtime role. Every test creates its own rows.
 */
@IntegrationTest
class MfaRecoveryCodeTenantMigrationTest {

    private static final String INSERT =
            "INSERT INTO mfa_recovery_code (organization_id, employee_id, code_hash)"
                    + " VALUES (?, ?, ?) RETURNING id";

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

    private Long insertCode(UUID org, UUID employee) {
        return jdbc.queryForObject(INSERT, Long.class, org, employee, "hash-" + UUID.randomUUID());
    }

    @Test
    void v20AppliesCleanly() {
        Integer applied =
                jdbc.queryForObject(
                        "SELECT count(*) FROM flyway_schema_history WHERE version = '20' AND"
                                + " success",
                        Integer.class);

        assertThat(applied).isEqualTo(1);
    }

    @Test
    void theNewColumnsHaveTheApprovedTypesAndNullability() {
        Map<String, Object> organization =
                jdbc.queryForMap(
                        "SELECT data_type, is_nullable FROM information_schema.columns"
                                + " WHERE table_name = 'mfa_recovery_code'"
                                + " AND column_name = 'organization_id'");
        Map<String, Object> invalidated =
                jdbc.queryForMap(
                        "SELECT data_type, is_nullable FROM information_schema.columns"
                                + " WHERE table_name = 'mfa_recovery_code'"
                                + " AND column_name = 'invalidated_at'");

        assertThat(organization)
                .containsEntry("data_type", "uuid")
                .containsEntry("is_nullable", "NO");
        assertThat(invalidated)
                .containsEntry("data_type", "timestamp with time zone")
                .containsEntry("is_nullable", "YES");
    }

    @Test
    void theOrganizationIsRequired() {
        UUID org = insertOrganization();
        UUID employee = insertEmployee(org);

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO mfa_recovery_code (employee_id, code_hash)"
                                                + " VALUES (?, ?)",
                                        employee,
                                        "hash-" + UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(
                        e ->
                                assertThat(SqlErrors.sqlState(e))
                                        .isEqualTo(SqlErrors.NOT_NULL_VIOLATION));
    }

    @Test
    void aCodeCannotBelongToAnEmployeeOfAnotherOrganization() {
        UUID org = insertOrganization();
        UUID employeeOfAnother = insertEmployee(insertOrganization());

        assertThatThrownBy(() -> insertCode(org, employeeOfAnother))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(SqlErrors.sqlState(e)).isEqualTo("23503"))
                .message()
                .contains("fk_mfa_recovery_code_employee_in_organization");
    }

    @Test
    void aCodeCanBeUsedOrInvalidatedButNotBoth() {
        UUID org = insertOrganization();
        UUID employee = insertEmployee(org);
        Long used = insertCode(org, employee);
        Long invalidated = insertCode(org, employee);

        assertThat(jdbc.update("UPDATE mfa_recovery_code SET used_at = now() WHERE id = ?", used))
                .isEqualTo(1);
        assertThat(
                        jdbc.update(
                                "UPDATE mfa_recovery_code SET invalidated_at = now() WHERE id = ?",
                                invalidated))
                .isEqualTo(1);

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE mfa_recovery_code SET invalidated_at = now()"
                                                + " WHERE id = ?",
                                        used))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(
                        e -> assertThat(SqlErrors.sqlState(e)).isEqualTo(SqlErrors.CHECK_VIOLATION))
                .message()
                .contains("ck_mfa_recovery_code_not_used_and_invalidated");
    }

    @Test
    void theIndexesAreUnchanged() {
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

    @Test
    void theNewColumnsAreDocumented() {
        for (String column : new String[] {"organization_id", "invalidated_at"}) {
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT col_description('mfa_recovery_code'::regclass,"
                                            + " ordinal_position) FROM information_schema.columns"
                                            + " WHERE table_name = 'mfa_recovery_code'"
                                            + " AND column_name = ?",
                                    String.class,
                                    column))
                    .as(column)
                    .contains("b2-7 (V20)");
        }
    }

    /**
     * A code written before V20 gets its organization from its employee. Needs a database migrated
     * only to V19 with a row inserted before V20 runs, so it drives Flyway directly against its own
     * container (the {@code RefreshTokenRotationMigrationTest} pattern).
     */
    @Test
    void anExistingCodeIsBackfilledWithItsEmployeesOrganization() throws SQLException {
        PostgreSQLContainer postgres = TestcontainersConfiguration.newPostgresContainer();
        postgres.start();
        try {
            flywayTargeting(postgres, "19").migrate();

            String org;
            String code;
            try (Connection c = superuser(postgres)) {
                org =
                        queryOne(
                                c,
                                "INSERT INTO organization (name, login_key_normalized, timezone)"
                                        + " VALUES ('Acme Corp', 'acme-corp', 'Asia/Kolkata')"
                                        + " RETURNING id");
                String employee =
                        queryOne(
                                c,
                                "INSERT INTO employee (organization_id, employee_code, name,"
                                        + " email, email_normalized, role) VALUES ('"
                                        + org
                                        + "', 'E-1', 'Jane Doe', 'jane@example.com',"
                                        + " 'jane@example.com', 'EMPLOYEE') RETURNING id");
                code =
                        queryOne(
                                c,
                                "INSERT INTO mfa_recovery_code (employee_id, code_hash) VALUES ('"
                                        + employee
                                        + "', 'hash-1') RETURNING id");
            }

            flywayTargeting(postgres, "20").migrate();

            try (Connection c = superuser(postgres)) {
                assertThat(
                                queryOne(
                                        c,
                                        "SELECT organization_id FROM mfa_recovery_code WHERE id = "
                                                + code))
                        .isEqualTo(org);
            }
        } finally {
            postgres.stop();
        }
    }

    private static Flyway flywayTargeting(PostgreSQLContainer postgres, String target) {
        return Flyway.configure()
                .dataSource(
                        new DriverManagerDataSource(
                                postgres.getJdbcUrl(),
                                postgres.getUsername(),
                                postgres.getPassword()))
                .placeholders(Map.of("runtime_role", TestDatabaseRoles.RUNTIME_ROLE))
                .target(target)
                .load();
    }

    private static Connection superuser(PostgreSQLContainer postgres) throws SQLException {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static String queryOne(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
