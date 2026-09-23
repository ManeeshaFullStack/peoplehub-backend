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
 * What the least-privileged runtime role can and cannot do to {@code refresh_token} (b2-1, Spec
 * 8.1, 12), against real PostgreSQL and connected as that role. Mirrors {@code
 * EmployeeInvitationRuntimeRoleTest}'s coverage of the equivalent V10 table.
 */
@IntegrationTest
class RefreshTokenRuntimeRoleTest {

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

    private static Timestamp in(int amount, ChronoUnit unit) {
        return Timestamp.from(Instant.now().plus(amount, unit));
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

    private UUID insertToken(UUID org, UUID employee) throws SQLException {
        try (PreparedStatement ps =
                runtime.prepareStatement(
                        "INSERT INTO refresh_token (organization_id, employee_id, token_hash,"
                                + " family_id, expires_at, absolute_expires_at)"
                                + " VALUES (?, ?, ?, ?, ?, ?) RETURNING id")) {
            ps.setObject(1, org);
            ps.setObject(2, employee);
            ps.setString(3, "hash-" + UUID.randomUUID());
            ps.setObject(4, UUID.randomUUID());
            ps.setTimestamp(5, in(30, ChronoUnit.DAYS));
            ps.setTimestamp(6, in(90, ChronoUnit.DAYS));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    @Test
    void insertOnTheGrantedColumnsSucceedsAndSelectSucceeds() throws SQLException {
        UUID org = insertOrganization();
        UUID employee = insertEmployee(org);

        UUID id = insertToken(org, employee);

        try (PreparedStatement ps =
                runtime.prepareStatement("SELECT revoked FROM refresh_token WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean("revoked")).isFalse();
            }
        }
    }

    @Test
    void idAndCreatedAtCannotBeSuppliedOnInsert() throws SQLException {
        UUID org = insertOrganization();
        UUID employee = insertEmployee(org);

        assertDenied(
                "INSERT INTO refresh_token (id, employee_id, token_hash, family_id, expires_at,"
                        + " absolute_expires_at) VALUES (gen_random_uuid(), '"
                        + employee
                        + "', 'hash', gen_random_uuid(), now() + interval '30 days',"
                        + " now() + interval '90 days')");
        assertDenied(
                "INSERT INTO refresh_token (employee_id, token_hash, family_id, expires_at,"
                        + " absolute_expires_at, created_at) VALUES ('"
                        + employee
                        + "', 'hash', gen_random_uuid(), now() + interval '30 days',"
                        + " now() + interval '90 days', now())");
    }

    @Test
    void theRevocationColumnsCanBeUpdatedButNothingElseCan() throws SQLException {
        UUID org = insertOrganization();
        UUID employee = insertEmployee(org);
        UUID id = insertToken(org, employee);
        UUID successor = insertToken(org, employee);

        // Rotation (b2-3, V14): revoke the old token and point it at its successor.
        try (PreparedStatement ps =
                runtime.prepareStatement(
                        "UPDATE refresh_token SET revoked = true, revoked_at = now(),"
                                + " revoke_reason = 'ROTATED', replaced_by_id = ? WHERE id = ?")) {
            ps.setObject(1, successor);
            ps.setObject(2, id);
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }

        for (String assignment :
                new String[] {
                    "token_hash = 'new'",
                    "employee_id = gen_random_uuid()",
                    "organization_id = gen_random_uuid()",
                    "family_id = gen_random_uuid()",
                    "expires_at = now()",
                    "absolute_expires_at = now()",
                    "device_label = 'x'"
                }) {
            assertDenied("UPDATE refresh_token SET " + assignment + " WHERE id = '" + id + "'");
        }
    }

    @Test
    void deleteAndTruncateAreDenied() throws SQLException {
        UUID org = insertOrganization();
        UUID employee = insertEmployee(org);
        insertToken(org, employee);

        assertDenied("DELETE FROM refresh_token");
        assertDenied("TRUNCATE refresh_token");
    }

    @Test
    void constraintsApplyToTheRuntimeRoleToo() {
        UUID org = insertOrganization();
        UUID employee = insertEmployee(org);

        assertThatThrownBy(
                        () -> {
                            try (Statement s = runtime.createStatement()) {
                                s.execute(
                                        "INSERT INTO refresh_token (organization_id,"
                                                + " employee_id, token_hash, family_id,"
                                                + " expires_at, absolute_expires_at) VALUES ('"
                                                + org
                                                + "', '"
                                                + employee
                                                + "', '', gen_random_uuid(),"
                                                + " now() + interval '30 days',"
                                                + " now() + interval '90 days')");
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
                                        + " WHERE oid = 'public.refresh_token'::regclass")) {
            while (acl.next()) {
                assertThat(acl.getString(1)).doesNotStartWith("=");
            }
        }
    }
}
