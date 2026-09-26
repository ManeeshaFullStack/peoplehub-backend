package com.peoplehub.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.SqlErrors;
import com.peoplehub.support.TestDatabaseRoles;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * What the least-privileged runtime role may do with V20's recovery-code tenant binding and
 * invalidation (b2-7), against real PostgreSQL and connected as that role.
 */
@IntegrationTest
class MfaRecoveryCodeTenantRuntimeRoleTest {

    @Autowired private PostgreSQLContainer postgres;
    @Autowired private JdbcTemplate jdbc;

    private Connection runtime;

    @BeforeEach
    void connectAsRuntimeRole() throws SQLException {
        runtime = TestDatabaseRoles.runtimeConnection(postgres);
    }

    @AfterEach
    void close() throws SQLException {
        runtime.close();
    }

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

    private long insertCode(UUID org, UUID employee) throws SQLException {
        try (PreparedStatement ps =
                runtime.prepareStatement(
                        "INSERT INTO mfa_recovery_code (organization_id, employee_id, code_hash)"
                                + " VALUES (?, ?, ?) RETURNING id")) {
            ps.setObject(1, org);
            ps.setObject(2, employee);
            ps.setString(3, "hash-" + UUID.randomUUID());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void assertDenied(String sql) {
        assertThatThrownBy(
                        () -> {
                            try (Statement s = runtime.createStatement()) {
                                s.execute(sql);
                            }
                        })
                .as(sql)
                .satisfies(
                        e ->
                                assertThat(SqlErrors.sqlState(e))
                                        .isEqualTo(SqlErrors.INSUFFICIENT_PRIVILEGE));
    }

    @Test
    void theRuntimeRoleInsertsCodesWithTheirOrganization() throws SQLException {
        UUID org = insertOrganization();
        UUID employee = insertEmployee(org);

        long id = insertCode(org, employee);

        assertThat(
                        jdbc.queryForObject(
                                "SELECT organization_id FROM mfa_recovery_code WHERE id = ?",
                                UUID.class,
                                id))
                .isEqualTo(org);
    }

    @Test
    void theRuntimeRoleCanInvalidateACodeButNotRewriteIt() throws SQLException {
        UUID org = insertOrganization();
        UUID employee = insertEmployee(org);
        long id = insertCode(org, employee);

        try (Statement s = runtime.createStatement()) {
            assertThat(
                            s.executeUpdate(
                                    "UPDATE mfa_recovery_code SET invalidated_at = now()"
                                            + " WHERE id = "
                                            + id))
                    .isEqualTo(1);
        }

        assertDenied("UPDATE mfa_recovery_code SET code_hash = 'other' WHERE id = " + id);
        assertDenied(
                "UPDATE mfa_recovery_code SET organization_id = gen_random_uuid() WHERE id = "
                        + id);
        assertDenied(
                "UPDATE mfa_recovery_code SET employee_id = gen_random_uuid() WHERE id = " + id);
        assertDenied("UPDATE mfa_recovery_code SET created_at = now() WHERE id = " + id);
    }

    @Test
    void invalidatedAtCannotBeSuppliedOnInsert() {
        UUID org = insertOrganization();
        UUID employee = insertEmployee(org);

        assertDenied(
                "INSERT INTO mfa_recovery_code (organization_id, employee_id, code_hash,"
                        + " invalidated_at) VALUES ('"
                        + org
                        + "', '"
                        + employee
                        + "', 'hash-x', now())");
    }

    @Test
    void usedAndInvalidatedCodesAreNeverDeleted() throws SQLException {
        UUID org = insertOrganization();
        insertCode(org, insertEmployee(org));

        assertDenied("DELETE FROM mfa_recovery_code");
        assertDenied("TRUNCATE mfa_recovery_code");
    }

    @Test
    void theTenantBindingAppliesToTheRuntimeRoleToo() {
        UUID org = insertOrganization();
        UUID employeeOfAnother = insertEmployee(insertOrganization());

        assertThatThrownBy(() -> insertCode(org, employeeOfAnother))
                .satisfies(e -> assertThat(SqlErrors.sqlState(e)).isEqualTo("23503"));
    }
}
