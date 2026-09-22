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
 * What the least-privileged runtime role can and cannot do to {@code email_outbox} (b1-1, Spec
 * 9.2), against real PostgreSQL and connected as that role. Mirrors {@code
 * AuditLogRuntimeRoleTest}'s coverage of the equivalent b0-6 role. Unlike {@code audit_log}, the
 * role has no UPDATE here yet: b1-1 ships nothing that transitions a row's status, so that
 * privilege is not granted until the b1-2 processor job needs it (V4's own comments). A rejection
 * here is the database's privilege check: the message says "permission denied".
 */
@IntegrationTest
class EmailOutboxRuntimeRoleTest {

    private static final String INSERT =
            "INSERT INTO email_outbox (organization_id, recipient, type) VALUES (?, 'jane@example.com',"
                    + " 'SOMETHING_HAPPENED')";

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

    private int insertRow(UUID org) throws SQLException {
        try (PreparedStatement ps = runtime.prepareStatement(INSERT)) {
            ps.setObject(1, org);
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
                                        .isEqualTo(SqlErrors.INSUFFICIENT_PRIVILEGE))
                .satisfies(
                        e ->
                                assertThat(SqlErrors.sqlMessage(e))
                                        .containsAnyOf("permission denied", "must be owner"));
    }

    @Test
    void insertSucceeds() throws SQLException {
        assertThat(insertRow(UUID.randomUUID())).isEqualTo(1);
    }

    @Test
    void selectSucceeds() throws SQLException {
        UUID org = UUID.randomUUID();
        insertRow(org);

        try (PreparedStatement ps =
                runtime.prepareStatement(
                        "SELECT id, created_at, recipient, type, status, attempts FROM email_outbox"
                                + " WHERE organization_id = ?")) {
            ps.setObject(1, org);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong("id")).isPositive();
                assertThat(rs.getTimestamp("created_at")).isNotNull();
                assertThat(rs.getString("recipient")).isEqualTo("jane@example.com");
                assertThat(rs.getString("status")).isEqualTo("PENDING");
                assertThat(rs.getInt("attempts")).isZero();
                assertThat(rs.next()).isFalse();
            }
        }
    }

    @Test
    void theRowIdAndTimeAreGeneratedEvenThoughTheRoleCannotSupplyThem() throws SQLException {
        UUID org = UUID.randomUUID();
        insertRow(org);
        insertRow(org);

        try (PreparedStatement ps =
                runtime.prepareStatement(
                        "SELECT count(DISTINCT id), count(*) FROM email_outbox"
                                + " WHERE organization_id = ? AND created_at IS NOT NULL")) {
            ps.setObject(1, org);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getLong(1)).isEqualTo(2);
                assertThat(rs.getLong(2)).isEqualTo(2);
            }
        }
    }

    @Test
    void aCallerSuppliedIdIsRejected() {
        String org = UUID.randomUUID().toString();

        // Without OVERRIDING, GENERATED ALWAYS refuses before any privilege is even checked.
        assertThatThrownBy(
                        () -> {
                            try (Statement s = runtime.createStatement()) {
                                s.execute(
                                        "INSERT INTO email_outbox (id, organization_id, recipient,"
                                                + " type) VALUES (999999999, '"
                                                + org
                                                + "', 'jane@example.com', 'SOMETHING_HAPPENED')");
                            }
                        })
                .satisfies(e -> assertThat(SqlErrors.sqlState(e)).isEqualTo("428C9"));
        // With it, the missing INSERT privilege on the id column stops it.
        assertDenied(
                "INSERT INTO email_outbox (id, organization_id, recipient, type) OVERRIDING SYSTEM"
                        + " VALUE VALUES (999999999, '"
                        + org
                        + "', 'jane@example.com', 'SOMETHING_HAPPENED')");
    }

    @Test
    void aCallerSuppliedCreatedAtIsRejected() {
        String org = UUID.randomUUID().toString();

        assertDenied(
                "INSERT INTO email_outbox (organization_id, recipient, type, created_at) VALUES ('"
                        + org
                        + "', 'jane@example.com', 'SOMETHING_HAPPENED', now() - interval '10 years')");
        assertDenied(
                "INSERT INTO email_outbox (organization_id, recipient, type, created_at) VALUES ('"
                        + org
                        + "', 'jane@example.com', 'SOMETHING_HAPPENED', DEFAULT)");
    }

    @Test
    void
            theRoleCannotSetStatusAttemptsOrOtherProcessingColumnsAtInsertTimeEvenThoughTheyHaveDefaults() {
        String org = UUID.randomUUID().toString();

        // b1-1 grants INSERT on exactly organization_id, recipient, type, payload. Not these
        // columns,
        // even though each one has a default or allows NULL: the b1-2 processor job earns this
        // privilege when it exists, not before.
        assertDenied(
                "INSERT INTO email_outbox (organization_id, recipient, type, status) VALUES ('"
                        + org
                        + "', 'jane@example.com', 'SOMETHING_HAPPENED', 'PENDING')");
        assertDenied(
                "INSERT INTO email_outbox (organization_id, recipient, type, attempts) VALUES ('"
                        + org
                        + "', 'jane@example.com', 'SOMETHING_HAPPENED', 0)");
        assertDenied(
                "INSERT INTO email_outbox (organization_id, recipient, type, next_attempt_at)"
                        + " VALUES ('"
                        + org
                        + "', 'jane@example.com', 'SOMETHING_HAPPENED', now())");
    }

    @Test
    void updateDeleteAndTruncateAreAllDeniedForNow() throws SQLException {
        // Different from shedlock and eventually different from this same table once b1-2 lands:
        // today nothing in b1-1 needs UPDATE, so it is not granted.
        UUID org = UUID.randomUUID();
        insertRow(org);

        assertDenied("UPDATE email_outbox SET status = 'SENT'");
        assertDenied("UPDATE email_outbox SET status = 'SENT' WHERE false");
        assertDenied("DELETE FROM email_outbox");
        assertDenied("DELETE FROM email_outbox WHERE false");
        assertDenied("TRUNCATE email_outbox");

        try (PreparedStatement ps =
                runtime.prepareStatement(
                        "SELECT status FROM email_outbox WHERE organization_id = ?")) {
            ps.setObject(1, org);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("PENDING");
            }
        }
    }

    @Test
    void constraintsApplyToTheRuntimeRoleToo() {
        assertThatThrownBy(
                        () -> {
                            try (Statement s = runtime.createStatement()) {
                                s.execute(
                                        "INSERT INTO email_outbox (organization_id, recipient, type)"
                                                + " VALUES (NULL, 'jane@example.com',"
                                                + " 'SOMETHING_HAPPENED')");
                            }
                        })
                .satisfies(
                        e ->
                                assertThat(SqlErrors.sqlState(e))
                                        .isEqualTo(SqlErrors.NOT_NULL_VIOLATION));
        assertThatThrownBy(
                        () -> {
                            try (Statement s = runtime.createStatement()) {
                                s.execute(
                                        "INSERT INTO email_outbox (organization_id, recipient, type)"
                                                + " VALUES ('00000000-0000-0000-0000-000000000000',"
                                                + " 'jane@example.com', 'SOMETHING_HAPPENED')");
                            }
                        })
                .satisfies(
                        e ->
                                assertThat(SqlErrors.sqlState(e))
                                        .isEqualTo(SqlErrors.CHECK_VIOLATION));
    }

    @Test
    void theRuntimeRoleCannotAlterOrDropTheTable() {
        assertDenied("ALTER TABLE email_outbox ADD COLUMN sneaky text");
        assertDenied("DROP TABLE email_outbox");
    }
}
