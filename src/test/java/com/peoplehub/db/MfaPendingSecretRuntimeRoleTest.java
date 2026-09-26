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
 * What the least-privileged runtime role may do with V23's pending secret (b2-7, B2-7/6), against
 * real PostgreSQL and connected as that role: write, promote and clear it, still under V23's check.
 */
@IntegrationTest
class MfaPendingSecretRuntimeRoleTest {

    @Autowired private PostgreSQLContainer postgres;
    @Autowired @PrivilegedFixture private JdbcTemplate jdbc;

    private Connection runtime;

    /** Binds this test's runtime connection to the organization it has just created (V25). */
    private UUID bound(UUID organizationId) {
        TestDatabaseRoles.bindTenant(runtime, organizationId);
        return organizationId;
    }

    @BeforeEach
    void connectAsRuntimeRole() throws SQLException {
        runtime = TestDatabaseRoles.runtimeConnection(postgres);
    }

    @AfterEach
    void close() throws SQLException {
        runtime.close();
    }

    private UUID insertEmployee(boolean enrolled) {
        UUID org =
                jdbc.queryForObject(
                        "INSERT INTO organization (name, login_key_normalized, timezone)"
                                + " VALUES (?, ?, ?) RETURNING id",
                        UUID.class,
                        "Acme Corp",
                        "org-" + UUID.randomUUID(),
                        "Asia/Kolkata");
        bound(org);
        String email = "jane-" + UUID.randomUUID() + "@example.com";
        UUID employee =
                jdbc.queryForObject(
                        "INSERT INTO employee (organization_id, employee_code, name, email,"
                                + " email_normalized, role)"
                                + " VALUES (?, ?, 'Jane Doe', ?, ?, 'EMPLOYEE') RETURNING id",
                        UUID.class,
                        org,
                        "E-" + UUID.randomUUID(),
                        email,
                        email);
        if (enrolled) {
            jdbc.update(
                    "UPDATE employee SET mfa_enabled = true, mfa_totp_secret = 'k1:active',"
                            + " mfa_enrolled_at = now() WHERE id = ?",
                    employee);
        }
        return employee;
    }

    private int update(String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = runtime.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            return ps.executeUpdate();
        }
    }

    @Test
    void theRuntimeRoleCanWriteAPendingSecret() throws SQLException {
        UUID employee = insertEmployee(true);

        assertThat(
                        update(
                                "UPDATE employee SET mfa_totp_pending_secret = 'k1:pending'"
                                        + " WHERE id = ?",
                                employee))
                .isEqualTo(1);
    }

    @Test
    void theRuntimeRoleCanPromoteAndClearIt() throws SQLException {
        UUID employee = insertEmployee(true);
        update("UPDATE employee SET mfa_totp_pending_secret = 'k1:pending' WHERE id = ?", employee);

        assertThat(
                        update(
                                "UPDATE employee SET mfa_totp_secret = mfa_totp_pending_secret,"
                                        + " mfa_totp_pending_secret = NULL, mfa_enrolled_at = now(),"
                                        + " mfa_totp_last_step = 58000000 WHERE id = ?",
                                employee))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT mfa_totp_secret FROM employee WHERE id = ?",
                                String.class,
                                employee))
                .isEqualTo("k1:pending");
    }

    @Test
    void theCheckAppliesToTheRuntimeRoleToo() {
        UUID employee = insertEmployee(false);

        assertThatThrownBy(
                        () ->
                                update(
                                        "UPDATE employee SET mfa_totp_pending_secret = 'k1:x'"
                                                + " WHERE id = ?",
                                        employee))
                .satisfies(
                        e ->
                                assertThat(SqlErrors.sqlState(e))
                                        .isEqualTo(SqlErrors.CHECK_VIOLATION));
    }
}
