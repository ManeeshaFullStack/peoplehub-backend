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
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * What the least-privileged runtime role may do with V19's MFA policy, MFA state, {@code role} and
 * revoke reasons (b2-7), against real PostgreSQL and connected as that role.
 */
@IntegrationTest
class MfaPolicyRuntimeRoleTest {

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

    private int update(String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = runtime.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            return ps.executeUpdate();
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
    void theRuntimeRoleCanChangeTheOrganizationPolicy() throws SQLException {
        UUID org = insertOrganization();

        assertThat(
                        update(
                                "UPDATE organization SET mfa_policy = 'REQUIRED_FOR_ADMINS'"
                                        + " WHERE id = ?",
                                org))
                .isEqualTo(1);
    }

    @Test
    void theRuntimeRoleCanRecordEnrollmentSelectionReplayStepAndDismissal() throws SQLException {
        UUID employee = insertEmployee(insertOrganization());

        assertThat(
                        update(
                                "UPDATE employee SET mfa_enabled = true,"
                                        + " mfa_totp_secret = 'k1:ciphertext', mfa_enrolled_at = now(),"
                                        + " mfa_totp_last_step = 57000000, mfa_required = true,"
                                        + " mfa_reminder_dismissed_at = now() WHERE id = ?",
                                employee))
                .isEqualTo(1);
    }

    @Test
    void theRuntimeRoleCanChangeARoleForPromotion() throws SQLException {
        UUID employee = insertEmployee(insertOrganization());

        assertThat(update("UPDATE employee SET role = 'ADMIN' WHERE id = ?", employee))
                .isEqualTo(1);
    }

    @Test
    void identityColumnsStayImmutable() {
        UUID org = insertOrganization();
        UUID employee = insertEmployee(org);

        assertDenied("UPDATE organization SET login_key_normalized = 'x' WHERE id = '" + org + "'");
        assertDenied("UPDATE organization SET id = gen_random_uuid() WHERE id = '" + org + "'");
        assertDenied("UPDATE employee SET employee_code = 'X' WHERE id = '" + employee + "'");
        assertDenied("UPDATE employee SET email = 'x@example.com' WHERE id = '" + employee + "'");
        assertDenied(
                "UPDATE employee SET email_normalized = 'x@example.com' WHERE id = '"
                        + employee
                        + "'");
        assertDenied(
                "UPDATE employee SET organization_id = gen_random_uuid() WHERE id = '"
                        + employee
                        + "'");
    }

    @Test
    void deleteAndTruncateStayDenied() {
        assertDenied("DELETE FROM organization");
        assertDenied("TRUNCATE organization");
        assertDenied("DELETE FROM employee");
        assertDenied("TRUNCATE employee");
    }

    @Test
    void theChecksApplyToTheRuntimeRoleToo() {
        UUID org = insertOrganization();
        UUID employee = insertEmployee(org);

        assertThatThrownBy(
                        () -> update("UPDATE organization SET mfa_policy = 'ON' WHERE id = ?", org))
                .satisfies(
                        e ->
                                assertThat(SqlErrors.sqlState(e))
                                        .isEqualTo(SqlErrors.CHECK_VIOLATION));
        assertThatThrownBy(
                        () ->
                                update(
                                        "UPDATE employee SET mfa_enabled = true WHERE id = ?",
                                        employee))
                .satisfies(
                        e ->
                                assertThat(SqlErrors.sqlState(e))
                                        .isEqualTo(SqlErrors.CHECK_VIOLATION));
    }

    @Test
    void theRuntimeRoleCanRevokeWithTheNewReasons() throws SQLException {
        UUID org = insertOrganization();
        UUID employee = insertEmployee(org);
        for (String reason : new String[] {"MFA_REQUIRED", "MFA_RESET", "ROLE_CHANGED"}) {
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
            assertThat(
                            update(
                                    "UPDATE refresh_token SET revoked = true, revoked_at = now(),"
                                            + " revoke_reason = ? WHERE id = ?",
                                    reason,
                                    token))
                    .as(reason)
                    .isEqualTo(1);
        }
    }
}
