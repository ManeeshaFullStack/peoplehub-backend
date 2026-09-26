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
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * What the least-privileged runtime role may do with V16's lockout columns and revoke reasons
 * (b2-5), against real PostgreSQL and connected as that role.
 */
@IntegrationTest
class EmployeeLockoutRuntimeRoleTest {

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
    void theRuntimeRoleCanRecordAndClearALock() throws SQLException {
        UUID employee = insertEmployee(bound(insertOrganization()));

        try (PreparedStatement ps =
                runtime.prepareStatement(
                        "UPDATE employee SET failed_login_count = failed_login_count + 1,"
                                + " locked_until = ? WHERE id = ?")) {
            ps.setTimestamp(1, Timestamp.from(Instant.now().plus(1, ChronoUnit.MINUTES)));
            ps.setObject(2, employee);
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }
        try (PreparedStatement ps =
                runtime.prepareStatement(
                        "UPDATE employee SET failed_login_count = 0, locked_until = NULL"
                                + " WHERE id = ?")) {
            ps.setObject(1, employee);
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }
    }

    @Test
    void theNonNegativeCheckAppliesToTheRuntimeRoleToo() {
        UUID employee = insertEmployee(bound(insertOrganization()));

        assertThatThrownBy(
                        () -> {
                            try (Statement s = runtime.createStatement()) {
                                s.execute(
                                        "UPDATE employee SET failed_login_count = -1 WHERE id = '"
                                                + employee
                                                + "'");
                            }
                        })
                .satisfies(
                        e ->
                                assertThat(SqlErrors.sqlState(e))
                                        .isEqualTo(SqlErrors.CHECK_VIOLATION));
    }

    @Test
    void theRuntimeRoleCanRevokeWithTheNewReasons() throws SQLException {
        UUID org = bound(insertOrganization());
        UUID employee = insertEmployee(org);
        for (String reason : new String[] {"PASSWORD_RESET", "PASSWORD_CHANGED"}) {
            UUID token =
                    jdbc.queryForObject(
                            "INSERT INTO refresh_token (organization_id, employee_id, token_hash,"
                                    + " family_id, expires_at, absolute_expires_at)"
                                    + " VALUES (?, ?, ?, gen_random_uuid(),"
                                    + " now() + interval '30 days', now() + interval '90 days')"
                                    + " RETURNING id",
                            UUID.class,
                            org,
                            employee,
                            "hash-" + UUID.randomUUID());
            try (PreparedStatement ps =
                    runtime.prepareStatement(
                            "UPDATE refresh_token SET revoked = true, revoked_at = now(),"
                                    + " revoke_reason = ? WHERE id = ?")) {
                ps.setString(1, reason);
                ps.setObject(2, token);
                assertThat(ps.executeUpdate()).as(reason).isEqualTo(1);
            }
        }
    }
}
