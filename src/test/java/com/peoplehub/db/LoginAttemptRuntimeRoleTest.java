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
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * What the least-privileged runtime role can and cannot do to {@code login_attempt} (b2-1, Spec
 * 8.2, 15), against real PostgreSQL and connected as that role. Mirrors {@code
 * AuditLogRuntimeRoleTest}'s coverage of the equivalent append-only table.
 */
@IntegrationTest
class LoginAttemptRuntimeRoleTest {

    @Autowired private PostgreSQLContainer postgres;

    private Connection runtime;

    @BeforeEach
    void connectAsRuntimeRole() throws SQLException {
        runtime = TestDatabaseRoles.runtimeConnection(postgres);
    }

    @AfterEach
    void close() throws SQLException {
        runtime.close();
    }

    private static String uniqueKey() {
        return "org-" + UUID.randomUUID();
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

    @Test
    void insertOnAllFourWriterColumnsSucceedsAndSelectSucceeds() throws SQLException {
        String key = uniqueKey();
        try (PreparedStatement ps =
                runtime.prepareStatement(
                        "INSERT INTO login_attempt (organization_login_key_attempted,"
                                + " email_attempted, ip, success) VALUES (?, ?, ?::inet, ?)")) {
            ps.setString(1, key);
            ps.setString(2, "jane@example.com");
            ps.setString(3, "203.0.113.7");
            ps.setBoolean(4, false);
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }

        try (PreparedStatement ps =
                runtime.prepareStatement(
                        "SELECT success FROM login_attempt"
                                + " WHERE organization_login_key_attempted = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean("success")).isFalse();
            }
        }
    }

    @Test
    void aCallerSuppliedIdIsRejected() {
        // Without OVERRIDING, GENERATED ALWAYS refuses before any privilege is even checked (same
        // precedent as EmailOutboxRuntimeRoleTest/AuditLogRuntimeRoleTest's equivalent test).
        assertThatThrownBy(
                        () -> {
                            try (Statement s = runtime.createStatement()) {
                                s.execute(
                                        "INSERT INTO login_attempt (id,"
                                                + " organization_login_key_attempted,"
                                                + " email_attempted, success) VALUES (999999999,"
                                                + " '"
                                                + uniqueKey()
                                                + "', 'jane@example.com', false)");
                            }
                        })
                .satisfies(e -> assertThat(SqlErrors.sqlState(e)).isEqualTo("428C9"));
        // With it, the missing INSERT privilege on the id column stops it.
        assertDenied(
                "INSERT INTO login_attempt (id, organization_login_key_attempted,"
                        + " email_attempted, success) OVERRIDING SYSTEM VALUE VALUES (999999999,"
                        + " '"
                        + uniqueKey()
                        + "', 'jane@example.com', false)");
    }

    @Test
    void occurredAtCannotBeSuppliedOnInsertEvenThoughItHasADefault() {
        assertDenied(
                "INSERT INTO login_attempt (organization_login_key_attempted, email_attempted,"
                        + " success, occurred_at) VALUES ('"
                        + uniqueKey()
                        + "', 'jane@example.com', false, now() - interval '1 year')");
    }

    @Test
    void updateDeleteAndTruncateAreAllDenied() throws SQLException {
        String key = uniqueKey();
        try (PreparedStatement ps =
                runtime.prepareStatement(
                        "INSERT INTO login_attempt (organization_login_key_attempted,"
                                + " email_attempted, success) VALUES (?, ?, ?)")) {
            ps.setString(1, key);
            ps.setString(2, "jane@example.com");
            ps.setBoolean(3, false);
            ps.executeUpdate();
        }

        assertDenied("UPDATE login_attempt SET success = true");
        assertDenied("DELETE FROM login_attempt");
        assertDenied("TRUNCATE login_attempt");
    }

    @Test
    void constraintsApplyToTheRuntimeRoleToo() {
        assertThatThrownBy(
                        () -> {
                            try (Statement s = runtime.createStatement()) {
                                s.execute(
                                        "INSERT INTO login_attempt"
                                                + " (organization_login_key_attempted,"
                                                + " email_attempted, success) VALUES ('', 'jane@example.com',"
                                                + " false)");
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
                                        + " WHERE oid = 'public.login_attempt'::regclass")) {
            while (acl.next()) {
                assertThat(acl.getString(1)).doesNotStartWith("=");
            }
        }
    }
}
