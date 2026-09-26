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
 * What the least-privileged runtime role may do to {@code session_step_up} (b2-7, V22): insert and
 * read, nothing else, and never choose the verification time.
 */
@IntegrationTest
class SessionStepUpRuntimeRoleTest {

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

    private long insertStepUp(UUID org, UUID employee) throws SQLException {
        try (PreparedStatement ps =
                runtime.prepareStatement(
                        "INSERT INTO session_step_up (organization_id, employee_id, session_id,"
                                + " method) VALUES (?, ?, ?, 'PASSWORD_AND_TOTP') RETURNING id")) {
            ps.setObject(1, org);
            ps.setObject(2, employee);
            ps.setObject(3, UUID.randomUUID());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
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
    void theRuntimeRoleCanRecordAndReadAStepUp() throws SQLException {
        UUID org = insertOrganization();
        long id = insertStepUp(org, insertEmployee(org));

        try (PreparedStatement ps =
                runtime.prepareStatement("SELECT method FROM session_step_up WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("PASSWORD_AND_TOTP");
            }
        }
    }

    @Test
    void theVerificationTimeAndIdCannotBeSupplied() {
        UUID org = insertOrganization();
        UUID employee = insertEmployee(org);

        assertDenied(
                "INSERT INTO session_step_up (organization_id, employee_id, session_id, method,"
                        + " verified_at) VALUES ('"
                        + org
                        + "', '"
                        + employee
                        + "', gen_random_uuid(), 'PASSWORD', now() + interval '1 hour')");
        assertDenied(
                "INSERT INTO session_step_up (id, organization_id, employee_id, session_id, method)"
                        + " OVERRIDING SYSTEM VALUE VALUES (999999999, '"
                        + org
                        + "', '"
                        + employee
                        + "', gen_random_uuid(), 'PASSWORD')");
    }

    @Test
    void aStepUpCanNeverBeChangedOrRemoved() throws SQLException {
        UUID org = insertOrganization();
        long id = insertStepUp(org, insertEmployee(org));

        for (String assignment :
                new String[] {
                    "verified_at = now() + interval '1 hour'",
                    "method = 'PASSWORD'",
                    "session_id = gen_random_uuid()",
                    "employee_id = gen_random_uuid()",
                    "organization_id = gen_random_uuid()"
                }) {
            assertDenied("UPDATE session_step_up SET " + assignment + " WHERE id = " + id);
        }
        assertDenied("DELETE FROM session_step_up");
        assertDenied("TRUNCATE session_step_up");
    }

    @Test
    void theTenantBindingAppliesToTheRuntimeRoleToo() {
        UUID org = insertOrganization();
        UUID employeeOfAnother = insertEmployee(insertOrganization());

        assertThatThrownBy(() -> insertStepUp(org, employeeOfAnother))
                .satisfies(e -> assertThat(SqlErrors.sqlState(e)).isEqualTo("23503"));
    }
}
