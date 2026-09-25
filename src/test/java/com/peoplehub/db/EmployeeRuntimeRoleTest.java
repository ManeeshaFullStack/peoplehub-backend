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
 * What the least-privileged runtime role can and cannot do to {@code employee} (b2-1, Spec 3.2,
 * 12.1), against real PostgreSQL and connected as that role. Mirrors {@code
 * OrganizationRuntimeRoleTest}'s coverage of the equivalent V8 table. Every insert uses a fresh
 * unique email/employee_code per test (the table's own tenant-scoped unique keys), never a shared
 * literal, since {@code @IntegrationTest} does not roll back between methods.
 *
 * <p>This is the first {@code *RuntimeRoleTest} whose table has a real FK to another application
 * table, so its {@code organization} fixture row must be inserted as whoever actually created that
 * table in this test context -- the container's default superuser (the autowired {@link
 * JdbcTemplate}), not {@link TestDatabaseRoles#ownerConnection}: per B0-6/3, most tests (this one
 * included) keep the container's own superuser for both Flyway and the app, so {@code
 * TestDatabaseRoles.OWNER_ROLE} has no privileges on any table here -- only the dedicated
 * privilege-boundary tests (like {@code TwoRoleWiringTest}) start their own application connected
 * as that role.
 */
@IntegrationTest
class EmployeeRuntimeRoleTest {

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

    private static String uniqueEmail() {
        return "jane-" + UUID.randomUUID() + "@example.com";
    }

    private static String uniqueCode() {
        return "E-" + UUID.randomUUID();
    }

    /** A real organization row, inserted through the superuser-backed JdbcTemplate. */
    private UUID insertOrganization() {
        return jdbc.queryForObject(
                "INSERT INTO organization (name, login_key_normalized, timezone)"
                        + " VALUES ('Acme Corp', ?, 'Asia/Kolkata') RETURNING id",
                UUID.class,
                "org-" + UUID.randomUUID());
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

    @Test
    void insertOnTheGrantedColumnsSucceedsAndSelectSucceeds() throws SQLException {
        UUID org = insertOrganization();
        String email = uniqueEmail();
        UUID id;
        try (PreparedStatement ps =
                runtime.prepareStatement(
                        "INSERT INTO employee (organization_id, employee_code, name, email,"
                                + " email_normalized, status, role, join_date) VALUES (?, ?,"
                                + " 'Jane Doe', ?, ?, 'INVITED', 'EMPLOYEE', CURRENT_DATE)"
                                + " RETURNING id")) {
            ps.setObject(1, org);
            ps.setString(2, uniqueCode());
            ps.setString(3, email);
            ps.setString(4, email);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                id = (UUID) rs.getObject("id");
            }
        }

        try (PreparedStatement ps =
                runtime.prepareStatement("SELECT name FROM employee WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("name")).isEqualTo("Jane Doe");
            }
        }
    }

    @Test
    void idCannotBeSuppliedOnInsertEvenThoughItHasADefault() throws SQLException {
        UUID org = insertOrganization();
        String email = uniqueEmail();

        assertDenied(
                "INSERT INTO employee (id, organization_id, employee_code, name, email,"
                        + " email_normalized, role) VALUES (gen_random_uuid(), '"
                        + org
                        + "', '"
                        + uniqueCode()
                        + "', 'Jane Doe', '"
                        + email
                        + "', '"
                        + email
                        + "', 'EMPLOYEE')");
    }

    @Test
    void employeeCodeCannotBeUpdatedEvenThoughInsertIsGranted() throws SQLException {
        UUID org = insertOrganization();
        UUID id = insertEmployee(org);

        assertDenied("UPDATE employee SET employee_code = 'NEW-CODE' WHERE id = '" + id + "'");
    }

    @Test
    void emailCannotBeUpdated() throws SQLException {
        UUID org = insertOrganization();
        UUID id = insertEmployee(org);

        // role became updatable in b2-7 (V19, promotion): MfaPolicyRuntimeRoleTest.
        assertDenied("UPDATE employee SET email = 'new@example.com' WHERE id = '" + id + "'");
        assertDenied(
                "UPDATE employee SET email_normalized = 'new@example.com' WHERE id = '" + id + "'");
    }

    @Test
    void passwordHashMfaFieldsWelcomeSeenAtAndDepartmentCanBeUpdated() throws SQLException {
        UUID org = insertOrganization();
        UUID id = insertEmployee(org);

        try (PreparedStatement ps =
                runtime.prepareStatement(
                        "UPDATE employee SET password_hash = 'hash', mfa_enabled = true,"
                                + " mfa_totp_secret = 'ciphertext', mfa_enrolled_at = now(),"
                                + " welcome_seen_at = now(),"
                                + " status = 'ACTIVE', updated_at = now() WHERE id = ?")) {
            ps.setObject(1, id);
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }

        try (PreparedStatement ps =
                runtime.prepareStatement("SELECT status FROM employee WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("status")).isEqualTo("ACTIVE");
            }
        }
    }

    @Test
    void deleteAndTruncateAreDenied() throws SQLException {
        UUID org = insertOrganization();
        insertEmployee(org);

        assertDenied("DELETE FROM employee");
        assertDenied("TRUNCATE employee");
    }

    @Test
    void constraintsApplyToTheRuntimeRoleToo() throws SQLException {
        UUID org = insertOrganization();

        assertThatThrownBy(
                        () -> {
                            try (Statement s = runtime.createStatement()) {
                                s.execute(
                                        "INSERT INTO employee (organization_id, employee_code,"
                                                + " name, email, email_normalized, role) VALUES ('"
                                                + org
                                                + "', '"
                                                + uniqueCode()
                                                + "', 'Jane Doe', '"
                                                + uniqueEmail()
                                                + "', '"
                                                + uniqueEmail()
                                                + "', 'BOGUS_ROLE')");
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
                                        + " WHERE oid = 'public.employee'::regclass")) {
            while (acl.next()) {
                assertThat(acl.getString(1)).doesNotStartWith("=");
            }
        }
    }

    private UUID insertEmployee(UUID org) throws SQLException {
        String email = uniqueEmail();
        try (PreparedStatement ps =
                runtime.prepareStatement(
                        "INSERT INTO employee (organization_id, employee_code, name, email,"
                                + " email_normalized, role) VALUES (?, ?, 'Jane Doe', ?, ?,"
                                + " 'EMPLOYEE') RETURNING id")) {
            ps.setObject(1, org);
            ps.setString(2, uniqueCode());
            ps.setString(3, email);
            ps.setString(4, email);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }
}
