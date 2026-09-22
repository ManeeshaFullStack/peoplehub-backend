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
 * What the least-privileged runtime role can and cannot do to {@code email_suppression} (b1-4, Spec
 * 9.2), against real PostgreSQL and connected as that role. Mirrors {@code
 * NotificationRuntimeRoleTest}'s coverage of the equivalent V6 tables.
 *
 * <p>Unlike {@code email_outbox}/{@code audit_log}, this table's primary key is the email address
 * itself rather than a generated id, and the runtime role has no DELETE (that is the point of this
 * class). {@code @IntegrationTest} does not roll back between methods, so each test that inserts a
 * row uses its own unique, disposable address instead of the shared "jane@example.com" literal, the
 * same way the other runtime-role tests scope by a fresh random organization id per test.
 */
@IntegrationTest
class EmailSuppressionRuntimeRoleTest {

    @Autowired private PostgreSQLContainer postgres;

    private Connection runtime;

    @BeforeEach
    void connectAsRuntimeRole() throws SQLException {
        runtime = TestDatabaseRoles.runtimeConnection(postgres);
    }

    private static String uniqueEmail() {
        return UUID.randomUUID() + "@example.com";
    }

    @AfterEach
    void close() throws SQLException {
        runtime.close();
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
    void insertOnEmailAndReasonSucceedsAndSelectSucceeds() throws SQLException {
        String email = uniqueEmail();
        try (PreparedStatement ps =
                runtime.prepareStatement(
                        "INSERT INTO email_suppression (email, reason) VALUES (?, 'BOUNCE')")) {
            ps.setString(1, email);
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }

        try (PreparedStatement ps =
                runtime.prepareStatement(
                        "SELECT reason, since FROM email_suppression WHERE email = ?")) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("reason")).isEqualTo("BOUNCE");
                assertThat(rs.getTimestamp("since")).isNotNull();
            }
        }
    }

    @Test
    void sinceCannotBeSuppliedOnInsertEvenThoughItHasADefault() {
        assertDenied(
                "INSERT INTO email_suppression (email, reason, since)"
                        + " VALUES ('"
                        + uniqueEmail()
                        + "', 'BOUNCE', now())");
    }

    @Test
    void updateDeleteAndTruncateAreAllDenied() throws SQLException {
        try (PreparedStatement ps =
                runtime.prepareStatement(
                        "INSERT INTO email_suppression (email, reason) VALUES (?, 'BOUNCE')")) {
            ps.setString(1, uniqueEmail());
            ps.executeUpdate();
        }

        assertDenied("UPDATE email_suppression SET reason = 'COMPLAINT'");
        assertDenied("DELETE FROM email_suppression");
        assertDenied("TRUNCATE email_suppression");
    }

    @Test
    void constraintsApplyToTheRuntimeRoleToo() {
        assertThatThrownBy(
                        () -> {
                            try (Statement s = runtime.createStatement()) {
                                s.execute(
                                        "INSERT INTO email_suppression (email, reason)"
                                                + " VALUES ('bad@example.com', 'NOT_A_REASON')");
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
                                        + " WHERE oid = 'public.email_suppression'::regclass")) {
            while (acl.next()) {
                assertThat(acl.getString(1)).doesNotStartWith("=");
            }
        }
    }
}
