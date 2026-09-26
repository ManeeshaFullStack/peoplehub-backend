package com.peoplehub.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.PrivilegedFixture;
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
 * What the least-privileged runtime role may do to {@code mfa_challenge} (b2-7, V21), against real
 * PostgreSQL and connected as that role.
 */
@IntegrationTest
class MfaChallengeRuntimeRoleTest {

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

    private UUID insertChallenge(UUID org, UUID employee) throws SQLException {
        try (PreparedStatement ps =
                runtime.prepareStatement(
                        "INSERT INTO mfa_challenge (organization_id, employee_id, token_hash,"
                                + " purpose, device_label, ip, expires_at) VALUES (?, ?, ?,"
                                + " 'CHALLENGE', 'Chrome on Windows', '203.0.113.7'::inet,"
                                + " now() + interval '5 minutes') RETURNING id")) {
            ps.setObject(1, org);
            ps.setObject(2, employee);
            ps.setString(3, "hash-" + UUID.randomUUID());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject(1, UUID.class);
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
    void theRuntimeRoleCanCreateAndReadAChallenge() throws SQLException {
        UUID org = bound(insertOrganization());
        UUID id = insertChallenge(org, insertEmployee(org));

        try (PreparedStatement ps =
                runtime.prepareStatement("SELECT purpose FROM mfa_challenge WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("CHALLENGE");
            }
        }
    }

    @Test
    void generatedAndStateColumnsCannotBeSuppliedOnInsert() {
        UUID org = bound(insertOrganization());
        UUID employee = insertEmployee(org);
        String base =
                "INSERT INTO mfa_challenge (organization_id, employee_id, token_hash, purpose,"
                        + " expires_at, %s) VALUES ('"
                        + org
                        + "', '"
                        + employee
                        + "', 'hash-x', 'CHALLENGE', now() + interval '5 minutes', %s)";

        assertDenied(base.formatted("id", "gen_random_uuid()"));
        assertDenied(base.formatted("created_at", "now()"));
        assertDenied(base.formatted("failed_attempts", "3"));
        assertDenied(base.formatted("consumed_at", "now()"));
        assertDenied(base.formatted("invalidated_at", "now()"));
    }

    @Test
    void onlyTheStateColumnsCanBeUpdated() throws SQLException {
        UUID org = bound(insertOrganization());
        UUID employee = insertEmployee(org);
        UUID consumed = insertChallenge(org, employee);
        UUID invalidated = insertChallenge(org, employee);

        try (Statement s = runtime.createStatement()) {
            assertThat(
                            s.executeUpdate(
                                    "UPDATE mfa_challenge SET failed_attempts = failed_attempts + 1,"
                                            + " consumed_at = now() WHERE id = '"
                                            + consumed
                                            + "'"))
                    .isEqualTo(1);
            assertThat(
                            s.executeUpdate(
                                    "UPDATE mfa_challenge SET invalidated_at = now() WHERE id = '"
                                            + invalidated
                                            + "'"))
                    .isEqualTo(1);
        }

        for (String assignment :
                new String[] {
                    "token_hash = 'other'",
                    "purpose = 'ENROLL'",
                    "expires_at = now() + interval '1 day'",
                    "device_label = 'x'",
                    "ip = '198.51.100.1'::inet",
                    "employee_id = gen_random_uuid()",
                    "organization_id = gen_random_uuid()",
                    "created_at = now()"
                }) {
            assertDenied(
                    "UPDATE mfa_challenge SET " + assignment + " WHERE id = '" + consumed + "'");
        }
    }

    @Test
    void challengesAreNeverDeleted() throws SQLException {
        UUID org = bound(insertOrganization());
        insertChallenge(org, insertEmployee(org));

        assertDenied("DELETE FROM mfa_challenge");
        assertDenied("TRUNCATE mfa_challenge");
    }

    @Test
    void theTenantBindingAppliesToTheRuntimeRoleToo() {
        UUID org = bound(insertOrganization());
        UUID employeeOfAnother = insertEmployee(bound(insertOrganization()));

        // Bound to the row's own organization, so the composite foreign key is what refuses it.
        bound(org);
        assertThatThrownBy(() -> insertChallenge(org, employeeOfAnother))
                .satisfies(e -> assertThat(SqlErrors.sqlState(e)).isEqualTo("23503"));
    }
}
