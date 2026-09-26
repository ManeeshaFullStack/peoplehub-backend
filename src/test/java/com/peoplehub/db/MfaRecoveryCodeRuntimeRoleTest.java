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
 * What the least-privileged runtime role can and cannot do to {@code mfa_recovery_code} (b2-1, Spec
 * 8.3, 12), against real PostgreSQL and connected as that role. Mirrors {@code
 * RefreshTokenRuntimeRoleTest}'s coverage of the equivalent table.
 */
@IntegrationTest
class MfaRecoveryCodeRuntimeRoleTest {

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
                                        .isEqualTo(SqlErrors.INSUFFICIENT_PRIVILEGE))
                .satisfies(
                        e ->
                                assertThat(SqlErrors.sqlMessage(e))
                                        .containsAnyOf("permission denied", "must be owner"));
    }

    // Since V20 every insert names organization_id, which is NOT NULL
    // (MfaRecoveryCodeTenantRuntimeRoleTest).
    private Long insertCode(UUID org, UUID employee) throws SQLException {
        try (PreparedStatement ps =
                runtime.prepareStatement(
                        "INSERT INTO mfa_recovery_code (organization_id, employee_id, code_hash)"
                                + " VALUES (?, ?, ?) RETURNING id")) {
            ps.setObject(1, org);
            ps.setObject(2, employee);
            ps.setString(3, "hash-" + UUID.randomUUID());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong("id");
            }
        }
    }

    @Test
    void insertOnEmployeeIdAndCodeHashSucceedsAndSelectSucceeds() throws SQLException {
        UUID org = insertOrganization();
        UUID employee = insertEmployee(org);

        Long id = insertCode(org, employee);

        try (PreparedStatement ps =
                runtime.prepareStatement("SELECT used_at FROM mfa_recovery_code WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getTimestamp("used_at")).isNull();
            }
        }
    }

    @Test
    void aCallerSuppliedIdIsRejected() {
        UUID org = insertOrganization();
        UUID employee = insertEmployee(org);

        // Without OVERRIDING, GENERATED ALWAYS refuses before any privilege is even checked.
        assertThatThrownBy(
                        () -> {
                            try (Statement s = runtime.createStatement()) {
                                s.execute(
                                        "INSERT INTO mfa_recovery_code (id, organization_id, employee_id,"
                                                + " code_hash) VALUES (999999999, '"
                                                + org
                                                + "', '"
                                                + employee
                                                + "', 'hash')");
                            }
                        })
                .satisfies(e -> assertThat(SqlErrors.sqlState(e)).isEqualTo("428C9"));
        // With it, the missing INSERT privilege on the id column stops it.
        assertDenied(
                "INSERT INTO mfa_recovery_code (id, organization_id, employee_id, code_hash)"
                        + " OVERRIDING SYSTEM VALUE VALUES (999999999, '"
                        + org
                        + "', '"
                        + employee
                        + "', 'hash')");
    }

    @Test
    void createdAtCannotBeSuppliedOnInsertEvenThoughItHasADefault() {
        UUID org = insertOrganization();
        UUID employee = insertEmployee(org);

        assertDenied(
                "INSERT INTO mfa_recovery_code (organization_id, employee_id, code_hash, created_at)"
                        + " VALUES ('"
                        + org
                        + "', '"
                        + employee
                        + "', 'hash', now() - interval '1 year')");
    }

    @Test
    void usedAtCanBeUpdatedButNothingElseCan() throws SQLException {
        UUID org = insertOrganization();
        UUID employee = insertEmployee(org);
        Long id = insertCode(org, employee);

        try (PreparedStatement ps =
                runtime.prepareStatement(
                        "UPDATE mfa_recovery_code SET used_at = now() WHERE id = ?")) {
            ps.setObject(1, id);
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }

        assertDenied("UPDATE mfa_recovery_code SET code_hash = 'new' WHERE id = " + id);
        assertDenied(
                "UPDATE mfa_recovery_code SET employee_id = gen_random_uuid() WHERE id = " + id);
    }

    @Test
    void deleteAndTruncateAreDenied() throws SQLException {
        UUID org = insertOrganization();
        UUID employee = insertEmployee(org);
        insertCode(org, employee);

        assertDenied("DELETE FROM mfa_recovery_code");
        assertDenied("TRUNCATE mfa_recovery_code");
    }

    @Test
    void constraintsApplyToTheRuntimeRoleToo() {
        UUID org = insertOrganization();
        UUID employee = insertEmployee(org);

        assertThatThrownBy(
                        () -> {
                            try (Statement s = runtime.createStatement()) {
                                s.execute(
                                        "INSERT INTO mfa_recovery_code (organization_id, employee_id, code_hash)"
                                                + " VALUES ('"
                                                + org
                                                + "', '"
                                                + employee
                                                + "', '')");
                            }
                        })
                .satisfies(
                        e ->
                                assertThat(SqlErrors.sqlState(e))
                                        .isEqualTo(SqlErrors.CHECK_VIOLATION));
    }

    @Test
    void grantsNothingToPublic() throws SQLException {
        try (Statement s = runtime.createStatement();
                ResultSet acl =
                        s.executeQuery(
                                "SELECT unnest(relacl)::text FROM pg_class"
                                        + " WHERE oid = 'public.mfa_recovery_code'::regclass")) {
            while (acl.next()) {
                assertThat(acl.getString(1)).doesNotStartWith("=");
            }
        }
    }
}
