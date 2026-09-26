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
 * What the least-privileged runtime role can and cannot do to {@code employee_invitation} (b2-1,
 * Spec 2.1.5, 3.3, 12.1), against real PostgreSQL and connected as that role. Mirrors {@code
 * EmployeeRuntimeRoleTest}'s coverage of the equivalent V9 table, including its use of the
 * superuser-backed {@link JdbcTemplate} for fixture rows (organization/employee) it does not itself
 * own the privilege to write.
 */
@IntegrationTest
class EmployeeInvitationRuntimeRoleTest {

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

    private UUID insertInviter(UUID org) {
        String email = "inviter-" + UUID.randomUUID() + "@example.com";
        return jdbc.queryForObject(
                "INSERT INTO employee (organization_id, employee_code, name, email,"
                        + " email_normalized, role) VALUES (?, ?, 'Jane Admin', ?, ?, 'ADMIN')"
                        + " RETURNING id",
                UUID.class,
                org,
                "E-" + UUID.randomUUID(),
                email,
                email);
    }

    private static String uniqueInviteeEmail() {
        return "invitee-" + UUID.randomUUID() + "@example.com";
    }

    private static Timestamp inOneHour() {
        return Timestamp.from(Instant.now().plus(1, ChronoUnit.HOURS));
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

    private UUID insertInvitation(UUID org, UUID inviter) throws SQLException {
        try (PreparedStatement ps =
                runtime.prepareStatement(
                        "INSERT INTO employee_invitation (organization_id, email_normalized,"
                                + " intended_role, token_hash, inviter_employee_id, expires_at)"
                                + " VALUES (?, ?, 'EMPLOYEE', ?, ?, ?) RETURNING id")) {
            ps.setObject(1, org);
            ps.setString(2, uniqueInviteeEmail());
            ps.setString(3, "hash-" + UUID.randomUUID());
            ps.setObject(4, inviter);
            ps.setTimestamp(5, inOneHour());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    @Test
    void insertOnTheGrantedColumnsSucceedsAndSelectSucceeds() throws SQLException {
        UUID org = insertOrganization();
        UUID inviter = insertInviter(org);

        UUID id = insertInvitation(org, inviter);

        try (PreparedStatement ps =
                runtime.prepareStatement(
                        "SELECT intended_role FROM employee_invitation WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("intended_role")).isEqualTo("EMPLOYEE");
            }
        }
    }

    @Test
    void idAndCreatedAtCannotBeSuppliedOnInsertEvenThoughTheyHaveDefaults() throws SQLException {
        UUID org = insertOrganization();
        UUID inviter = insertInviter(org);

        assertDenied(
                "INSERT INTO employee_invitation (id, organization_id, email_normalized,"
                        + " intended_role, token_hash, inviter_employee_id, expires_at) VALUES"
                        + " (gen_random_uuid(), '"
                        + org
                        + "', '"
                        + uniqueInviteeEmail()
                        + "', 'EMPLOYEE', 'hash', '"
                        + inviter
                        + "', now() + interval '1 hour')");
        assertDenied(
                "INSERT INTO employee_invitation (organization_id, email_normalized,"
                        + " intended_role, token_hash, inviter_employee_id, expires_at,"
                        + " created_at) VALUES ('"
                        + org
                        + "', '"
                        + uniqueInviteeEmail()
                        + "', 'EMPLOYEE', 'hash', '"
                        + inviter
                        + "', now() + interval '1 hour', now())");
    }

    @Test
    void consumedAtAndRevokedAtCanBeUpdated() throws SQLException {
        UUID org = insertOrganization();
        UUID inviter = insertInviter(org);
        UUID id = insertInvitation(org, inviter);

        try (PreparedStatement ps =
                runtime.prepareStatement(
                        "UPDATE employee_invitation SET consumed_at = now() WHERE id = ?")) {
            ps.setObject(1, id);
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }

        UUID id2 = insertInvitation(org, inviter);
        try (PreparedStatement ps =
                runtime.prepareStatement(
                        "UPDATE employee_invitation SET revoked_at = now() WHERE id = ?")) {
            ps.setObject(1, id2);
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }
    }

    @Test
    void otherColumnsCannotBeUpdated() throws SQLException {
        UUID org = insertOrganization();
        UUID inviter = insertInviter(org);
        UUID id = insertInvitation(org, inviter);

        assertDenied("UPDATE employee_invitation SET token_hash = 'new' WHERE id = '" + id + "'");
        assertDenied(
                "UPDATE employee_invitation SET intended_role = 'ADMIN' WHERE id = '" + id + "'");
        assertDenied(
                "UPDATE employee_invitation SET organization_id = gen_random_uuid()"
                        + " WHERE id = '"
                        + id
                        + "'");
    }

    @Test
    void deleteAndTruncateAreDenied() throws SQLException {
        UUID org = insertOrganization();
        UUID inviter = insertInviter(org);
        insertInvitation(org, inviter);

        assertDenied("DELETE FROM employee_invitation");
        assertDenied("TRUNCATE employee_invitation");
    }

    @Test
    void constraintsApplyToTheRuntimeRoleToo() {
        UUID org = insertOrganization();
        UUID inviter = insertInviter(org);

        assertThatThrownBy(
                        () -> {
                            try (Statement s = runtime.createStatement()) {
                                s.execute(
                                        "INSERT INTO employee_invitation (organization_id,"
                                                + " email_normalized, intended_role, token_hash,"
                                                + " inviter_employee_id, expires_at) VALUES ('"
                                                + org
                                                + "', '"
                                                + uniqueInviteeEmail()
                                                + "', 'SUPER_ADMIN', 'hash', '"
                                                + inviter
                                                + "', now() + interval '1 hour')");
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
                                        + " WHERE oid = 'public.employee_invitation'::regclass")) {
            while (acl.next()) {
                assertThat(acl.getString(1)).doesNotStartWith("=");
            }
        }
    }
}
