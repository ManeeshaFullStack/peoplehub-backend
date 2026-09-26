package com.peoplehub.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.PrivilegedFixture;
import com.peoplehub.support.SqlErrors;
import com.peoplehub.support.TestDatabaseRoles;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * What the least-privileged runtime role may do with V18's revoke reasons (b2-6, B2-6/6), against
 * real PostgreSQL and connected as that role: end one session (a whole family) with {@code
 * SESSION_REVOKED}, and every session of an employee with {@code DEACTIVATED}, using the existing
 * grants only. {@code RefreshTokenRuntimeRoleTest} already proves it can never DELETE or TRUNCATE.
 */
@IntegrationTest
class SessionRevokeReasonsRuntimeRoleTest {

    @Autowired private PostgreSQLContainer postgres;
    @Autowired @PrivilegedFixture private JdbcTemplate jdbc;

    private Connection runtime;

    @BeforeEach
    void connectAsRuntimeRole() throws SQLException {
        runtime = TestDatabaseRoles.runtimeConnection(postgres);
    }

    @AfterEach
    void close() throws SQLException {
        runtime.close();
    }

    private record Employee(UUID organizationId, UUID id) {}

    private Employee insertEmployee() {
        UUID org =
                jdbc.queryForObject(
                        "INSERT INTO organization (name, login_key_normalized, timezone)"
                                + " VALUES ('Acme Corp', ?, 'Asia/Kolkata') RETURNING id",
                        UUID.class,
                        "org-" + UUID.randomUUID());
        String email = "jane-" + UUID.randomUUID() + "@example.com";
        UUID employee =
                jdbc.queryForObject(
                        "INSERT INTO employee (organization_id, employee_code, name, email,"
                                + " email_normalized, role) VALUES (?, ?, 'Jane Doe', ?, ?,"
                                + " 'EMPLOYEE') RETURNING id",
                        UUID.class,
                        org,
                        "E-" + UUID.randomUUID(),
                        email,
                        email);
        return new Employee(org, employee);
    }

    private void insertToken(Employee employee, UUID family) {
        jdbc.update(
                "INSERT INTO refresh_token (organization_id, employee_id, token_hash, family_id,"
                        + " expires_at, absolute_expires_at) VALUES (?, ?, ?, ?,"
                        + " now() + interval '30 days', now() + interval '90 days')",
                employee.organizationId(),
                employee.id(),
                "hash-" + UUID.randomUUID(),
                family);
    }

    private int unrevoked(Employee employee) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM refresh_token WHERE employee_id = ? AND NOT revoked",
                Integer.class,
                employee.id());
    }

    @Test
    void theRuntimeRoleCanEndOneSessionWithSessionRevoked() throws SQLException {
        Employee employee = insertEmployee();
        UUID ended = UUID.randomUUID();
        UUID kept = UUID.randomUUID();
        insertToken(employee, ended);
        insertToken(employee, kept);

        try (PreparedStatement ps =
                runtime.prepareStatement(
                        "UPDATE refresh_token SET revoked = true, revoked_at = now(),"
                                + " revoke_reason = 'SESSION_REVOKED'"
                                + " WHERE family_id = ? AND employee_id = ? AND organization_id = ?"
                                + " AND NOT revoked")) {
            ps.setObject(1, ended);
            ps.setObject(2, employee.id());
            ps.setObject(3, employee.organizationId());
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }

        assertThat(unrevoked(employee)).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT revoke_reason FROM refresh_token WHERE family_id = ?",
                                String.class,
                                ended))
                .isEqualTo("SESSION_REVOKED");
    }

    @Test
    void theRuntimeRoleCanEndEverySessionOfAnEmployeeWithDeactivated() throws SQLException {
        Employee employee = insertEmployee();
        Employee other = insertEmployee();
        insertToken(employee, UUID.randomUUID());
        insertToken(employee, UUID.randomUUID());
        insertToken(other, UUID.randomUUID());

        try (PreparedStatement ps =
                runtime.prepareStatement(
                        "UPDATE refresh_token SET revoked = true, revoked_at = now(),"
                                + " revoke_reason = 'DEACTIVATED'"
                                + " WHERE employee_id = ? AND organization_id = ? AND NOT revoked")) {
            ps.setObject(1, employee.id());
            ps.setObject(2, employee.organizationId());
            assertThat(ps.executeUpdate()).isEqualTo(2);
        }

        assertThat(unrevoked(employee)).isZero();
        assertThat(unrevoked(other)).isEqualTo(1);
    }

    @Test
    void theRuntimeRoleCannotUseAnUnknownReason() {
        Employee employee = insertEmployee();
        insertToken(employee, UUID.randomUUID());

        assertThatThrownBy(
                        () -> {
                            try (PreparedStatement ps =
                                    runtime.prepareStatement(
                                            "UPDATE refresh_token SET revoked = true,"
                                                    + " revoked_at = now(), revoke_reason = 'EXPIRED'"
                                                    + " WHERE employee_id = ?")) {
                                ps.setObject(1, employee.id());
                                ps.executeUpdate();
                            }
                        })
                .satisfies(
                        e ->
                                assertThat(SqlErrors.sqlState(e))
                                        .isEqualTo(SqlErrors.CHECK_VIOLATION));
    }
}
