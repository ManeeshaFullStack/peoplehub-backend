package com.peoplehub.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.TestDatabaseRoles;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The least-privileged runtime role can take the employee row locks that login and refresh use
 * against a concurrent deactivation (b2-6, B2-6/12). PostgreSQL requires an UPDATE privilege on at
 * least one column of a table to lock its rows; this proves the existing column grants are enough,
 * with no new grant.
 */
@IntegrationTest
class ActiveEmployeeLockRuntimeRoleTest {

    @Autowired private PostgreSQLContainer postgres;
    @Autowired private JdbcTemplate jdbc;

    private Connection runtime;

    @BeforeEach
    void connectAsRuntimeRole() throws SQLException {
        runtime = TestDatabaseRoles.runtimeConnection(postgres);
        runtime.setAutoCommit(false);
    }

    @AfterEach
    void close() throws SQLException {
        runtime.rollback();
        runtime.close();
    }

    private UUID[] insertActiveEmployee() {
        UUID org =
                jdbc.queryForObject(
                        "INSERT INTO organization (name, login_key_normalized, timezone, status)"
                                + " VALUES ('Acme Corp', ?, 'Asia/Kolkata', 'ACTIVE') RETURNING id",
                        UUID.class,
                        "org-" + UUID.randomUUID());
        String email = "jane-" + UUID.randomUUID() + "@example.com";
        UUID employee =
                jdbc.queryForObject(
                        "INSERT INTO employee (organization_id, employee_code, name, email,"
                                + " email_normalized, role, status) VALUES (?, ?, 'Jane Doe', ?, ?,"
                                + " 'EMPLOYEE', 'ACTIVE') RETURNING id",
                        UUID.class,
                        org,
                        "E-" + UUID.randomUUID(),
                        email,
                        email);
        return new UUID[] {org, employee};
    }

    @Test
    void theRuntimeRoleCanTakeBothLocks() throws SQLException {
        UUID[] ids = insertActiveEmployee();

        for (String lock : new String[] {"FOR SHARE OF e", "FOR NO KEY UPDATE OF e"}) {
            try (PreparedStatement ps =
                    runtime.prepareStatement(
                            "SELECT e.role FROM employee e JOIN organization o"
                                    + " ON o.id = e.organization_id"
                                    + " WHERE e.id = ? AND e.organization_id = ?"
                                    + " AND e.status = 'ACTIVE' AND o.status = 'ACTIVE' "
                                    + lock)) {
                ps.setObject(1, ids[1]);
                ps.setObject(2, ids[0]);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).as(lock).isTrue();
                    assertThat(rs.getString(1)).isEqualTo("EMPLOYEE");
                }
            }
        }
    }
}
