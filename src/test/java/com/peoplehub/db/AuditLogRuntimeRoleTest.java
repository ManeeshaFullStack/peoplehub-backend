package com.peoplehub.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.PrivilegedFixture;
import com.peoplehub.support.SqlErrors;
import com.peoplehub.support.TestDatabaseRoles;
import com.peoplehub.support.TestOrganizations;
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
 * What the least-privileged runtime role can and cannot do to {@code audit_log} (B0-6/2, B0-6/4),
 * against real PostgreSQL and connected as that role. A rejection here is the database's privilege
 * check, which comes before the trigger: the message says "permission denied", not "append-only".
 *
 * <p>b2-1 (V12) gave {@code organization_id} a real FK, so every test that expects an insert to
 * succeed needs a real {@code organization} row first -- inserted through the superuser-backed
 * {@link JdbcTemplate} (the runtime role itself has no INSERT privilege on {@code organization}),
 * the same pattern {@code EmployeeRuntimeRoleTest} already established.
 */
@IntegrationTest
class AuditLogRuntimeRoleTest {

    private static final String INSERT =
            "INSERT INTO audit_log (organization_id, actor_id, action, target_type, target_id, ip,"
                    + " correlation_id, details) VALUES (?, 'job:test', 'SOMETHING_HAPPENED',"
                    + " 'EMPLOYEE', 'e-1', '203.0.113.7'::inet, 'corr-1', '{\"v\":1}'::jsonb)";

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
    void theConnectionReallyIsTheUnprivilegedRuntimeRole() throws SQLException {
        try (Statement s = runtime.createStatement();
                ResultSet rs =
                        s.executeQuery(
                                "SELECT current_user, r.rolsuper, r.rolcreaterole, r.rolcreatedb,"
                                        + " r.rolbypassrls FROM pg_roles r WHERE r.rolname ="
                                        + " current_user")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo(TestDatabaseRoles.RUNTIME_ROLE);
            assertThat(rs.getBoolean("rolsuper")).isFalse();
            assertThat(rs.getBoolean("rolcreaterole")).isFalse();
            assertThat(rs.getBoolean("rolcreatedb")).isFalse();
            assertThat(rs.getBoolean("rolbypassrls")).isFalse();
        }
    }

    @Test
    void insertSucceeds() throws SQLException {
        assertThat(insertRow(bound(TestOrganizations.insert(jdbc)))).isEqualTo(1);
    }

    @Test
    void selectSucceeds() throws SQLException {
        UUID org = bound(TestOrganizations.insert(jdbc));
        insertRow(org);

        try (PreparedStatement ps =
                runtime.prepareStatement(
                        "SELECT id, occurred_at, actor_id, action, host(ip), details::text"
                                + " FROM audit_log WHERE organization_id = ?")) {
            ps.setObject(1, org);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong("id")).isPositive();
                assertThat(rs.getTimestamp("occurred_at")).isNotNull();
                assertThat(rs.getString("actor_id")).isEqualTo("job:test");
                assertThat(rs.getString("host")).isEqualTo("203.0.113.7");
                assertThat(rs.next()).isFalse();
            }
        }
    }

    @Test
    void theRowIdAndTimeAreGeneratedEvenThoughTheRoleCannotSupplyThem() throws SQLException {
        // The identity column needs no privilege on its sequence or on `id` for the default to
        // apply.
        UUID org = bound(TestOrganizations.insert(jdbc));
        insertRow(org);
        insertRow(org);

        try (PreparedStatement ps =
                runtime.prepareStatement(
                        "SELECT count(DISTINCT id), count(*) FROM audit_log"
                                + " WHERE organization_id = ? AND occurred_at IS NOT NULL")) {
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
                                        "INSERT INTO audit_log (id, organization_id, actor_id,"
                                                + " action) VALUES (999999999, '"
                                                + org
                                                + "', 'job:test', 'SOMETHING_HAPPENED')");
                            }
                        })
                .satisfies(e -> assertThat(SqlErrors.sqlState(e)).isEqualTo("428C9"));
        // With it, the missing INSERT privilege on the id column stops it.
        assertDenied(
                "INSERT INTO audit_log (id, organization_id, actor_id, action) OVERRIDING SYSTEM"
                        + " VALUE VALUES (999999999, '"
                        + org
                        + "', 'job:test', 'SOMETHING_HAPPENED')");
    }

    @Test
    void aCallerSuppliedOccurredAtIsRejected() {
        String org = UUID.randomUUID().toString();

        assertDenied(
                "INSERT INTO audit_log (organization_id, actor_id, action, occurred_at) VALUES ('"
                        + org
                        + "', 'job:test', 'SOMETHING_HAPPENED', now() - interval '10 years')");
        assertDenied(
                "INSERT INTO audit_log (organization_id, actor_id, action, occurred_at) VALUES ('"
                        + org
                        + "', 'job:test', 'SOMETHING_HAPPENED', DEFAULT)");
    }

    @Test
    void updateDeleteAndTruncateAreDenied() throws SQLException {
        UUID org = bound(TestOrganizations.insert(jdbc));
        insertRow(org);

        assertDenied("UPDATE audit_log SET action = 'TAMPERED'");
        assertDenied("UPDATE audit_log SET action = 'TAMPERED' WHERE false");
        assertDenied("DELETE FROM audit_log");
        assertDenied("DELETE FROM audit_log WHERE false");
        assertDenied("TRUNCATE audit_log");
        assertDenied("TRUNCATE TABLE audit_log RESTART IDENTITY CASCADE");

        try (PreparedStatement ps =
                runtime.prepareStatement(
                        "SELECT action FROM audit_log WHERE organization_id = ?")) {
            ps.setObject(1, org);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("SOMETHING_HAPPENED");
            }
        }
    }

    @Test
    void constraintsApplyToTheRuntimeRoleToo() {
        // Bound to the nil id (V25), so the nil-id row passes the tenant policy and meets its
        // CHECK.
        bound(new UUID(0L, 0L));
        assertThatThrownBy(
                        () -> {
                            try (Statement s = runtime.createStatement()) {
                                s.execute(
                                        "INSERT INTO audit_log (organization_id, actor_id, action)"
                                                + " VALUES (NULL, 'job:test', 'SOMETHING_HAPPENED')");
                            }
                        })
                .satisfies(
                        e ->
                                assertThat(SqlErrors.sqlState(e))
                                        // V25: a NULL tenant can never match the bound one, so the
                                        // tenant policy refuses the
                                        // row before NOT NULL is reached (NOT NULL itself: the
                                        // migration tests).
                                        .isEqualTo(SqlErrors.INSUFFICIENT_PRIVILEGE));
        assertThatThrownBy(
                        () -> {
                            try (Statement s = runtime.createStatement()) {
                                s.execute(
                                        "INSERT INTO audit_log (organization_id, actor_id, action)"
                                                + " VALUES ('00000000-0000-0000-0000-000000000000',"
                                                + " 'job:test', 'SOMETHING_HAPPENED')");
                            }
                        })
                .satisfies(
                        e ->
                                assertThat(SqlErrors.sqlState(e))
                                        .isEqualTo(SqlErrors.CHECK_VIOLATION));
    }

    @Test
    void theRuntimeRoleCannotRemoveOrWeakenTheProtection() {
        // It does not own the table, so it cannot disable, drop or replace the trigger, and it is
        // not a
        // superuser, so it cannot switch triggers off with session_replication_role either.
        assertDenied("ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_append_only");
        assertDenied("ALTER TABLE audit_log DISABLE TRIGGER ALL");
        assertDenied("DROP TRIGGER trg_audit_log_append_only ON audit_log");
        assertDenied(
                "CREATE TRIGGER trg_other BEFORE INSERT ON audit_log FOR EACH STATEMENT EXECUTE"
                        + " FUNCTION audit_log_reject_change()");
        assertDenied("ALTER TABLE audit_log ADD COLUMN sneaky text");
        assertDenied("DROP TABLE audit_log");
        assertDenied("SET session_replication_role = replica");
        assertDenied(
                "CREATE OR REPLACE FUNCTION audit_log_reject_change() RETURNS trigger LANGUAGE"
                        + " plpgsql AS $$ BEGIN RETURN NULL; END $$");
        assertDenied("CREATE TABLE runtime_created (v int)");
    }
}
