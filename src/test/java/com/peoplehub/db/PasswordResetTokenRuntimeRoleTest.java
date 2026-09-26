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
 * What the least-privileged runtime role can and cannot do to {@code password_reset_token} (b2-5,
 * V17), against real PostgreSQL and connected as that role.
 */
@IntegrationTest
class PasswordResetTokenRuntimeRoleTest {

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

    private UUID insertToken(UUID org, UUID employee) throws SQLException {
        try (PreparedStatement ps =
                runtime.prepareStatement(
                        "INSERT INTO password_reset_token (organization_id, employee_id,"
                                + " token_hash, expires_at) VALUES (?, ?, ?, ?) RETURNING id")) {
            ps.setObject(1, org);
            ps.setObject(2, employee);
            ps.setString(3, "hash-" + UUID.randomUUID());
            ps.setTimestamp(4, Timestamp.from(Instant.now().plus(30, ChronoUnit.MINUTES)));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
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
    void insertOnTheGrantedColumnsAndSelectSucceed() throws SQLException {
        UUID org = insertOrganization();
        UUID id = insertToken(org, insertEmployee(org));

        try (PreparedStatement ps =
                runtime.prepareStatement(
                        "SELECT consumed_at FROM password_reset_token WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getTimestamp("consumed_at")).isNull();
            }
        }
    }

    @Test
    void generatedAndLifecycleColumnsCannotBeSuppliedOnInsert() {
        UUID org = insertOrganization();
        UUID employee = insertEmployee(org);
        String base =
                "INSERT INTO password_reset_token (organization_id, employee_id, token_hash,"
                        + " expires_at, %s) VALUES ('"
                        + org
                        + "', '"
                        + employee
                        + "', 'hash-x', now() + interval '30 minutes', %s)";

        assertDenied(base.formatted("id", "gen_random_uuid()"));
        assertDenied(base.formatted("created_at", "now()"));
        assertDenied(base.formatted("consumed_at", "now()"));
        assertDenied(base.formatted("invalidated_at", "now()"));
    }

    @Test
    void consumedAndInvalidatedCanBeSetButNothingElseChanged() throws SQLException {
        UUID org = insertOrganization();
        UUID employee = insertEmployee(org);
        UUID consumed = insertToken(org, employee);
        UUID invalidated = insertToken(org, employee);

        try (Statement s = runtime.createStatement()) {
            assertThat(
                            s.executeUpdate(
                                    "UPDATE password_reset_token SET consumed_at = now()"
                                            + " WHERE id = '"
                                            + consumed
                                            + "'"))
                    .isEqualTo(1);
            assertThat(
                            s.executeUpdate(
                                    "UPDATE password_reset_token SET invalidated_at = now()"
                                            + " WHERE id = '"
                                            + invalidated
                                            + "'"))
                    .isEqualTo(1);
        }

        for (String assignment :
                new String[] {
                    "token_hash = 'other'",
                    "expires_at = now() + interval '1 day'",
                    "employee_id = gen_random_uuid()",
                    "organization_id = gen_random_uuid()",
                    "created_at = now()"
                }) {
            assertDenied(
                    "UPDATE password_reset_token SET "
                            + assignment
                            + " WHERE id = '"
                            + consumed
                            + "'");
        }
    }

    @Test
    void deleteAndTruncateAreDenied() throws SQLException {
        UUID org = insertOrganization();
        insertToken(org, insertEmployee(org));

        assertDenied("DELETE FROM password_reset_token");
        assertDenied("TRUNCATE password_reset_token");
    }

    @Test
    void theConstraintsApplyToTheRuntimeRoleToo() {
        UUID org = insertOrganization();
        UUID employeeOfAnother = insertEmployee(insertOrganization());

        assertThatThrownBy(() -> insertToken(org, employeeOfAnother))
                .satisfies(e -> assertThat(SqlErrors.sqlState(e)).isEqualTo("23503"));
    }
}
