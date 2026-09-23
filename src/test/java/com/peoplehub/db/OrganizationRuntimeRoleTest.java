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
 * What the least-privileged runtime role can and cannot do to {@code organization} (b2-1, Spec
 * 2.1.1, 12.1), against real PostgreSQL and connected as that role. Mirrors {@code
 * EmailSuppressionRuntimeRoleTest}'s coverage of the equivalent V7 table, including its use of a
 * fresh unique login key per test (the table's own unique key) instead of a shared literal, since
 * {@code @IntegrationTest} does not roll back between methods.
 */
@IntegrationTest
class OrganizationRuntimeRoleTest {

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

    private static String uniqueLoginKey() {
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
    void insertOnNameLoginKeyAndTimezoneSucceedsAndSelectSucceeds() throws SQLException {
        String key = uniqueLoginKey();
        try (PreparedStatement ps =
                runtime.prepareStatement(
                        "INSERT INTO organization (name, login_key_normalized, timezone)"
                                + " VALUES (?, ?, ?)")) {
            ps.setString(1, "Acme Corp");
            ps.setString(2, key);
            ps.setString(3, "Asia/Kolkata");
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }

        try (PreparedStatement ps =
                runtime.prepareStatement(
                        "SELECT id, status FROM organization WHERE login_key_normalized = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getObject("id")).isNotNull();
                assertThat(rs.getString("status")).isEqualTo("PENDING_VERIFICATION");
            }
        }
    }

    @Test
    void idCannotBeSuppliedOnInsertEvenThoughItHasADefault() {
        assertDenied(
                "INSERT INTO organization (id, name, login_key_normalized, timezone)"
                        + " VALUES (gen_random_uuid(), 'Acme Corp', '"
                        + uniqueLoginKey()
                        + "', 'Asia/Kolkata')");
    }

    @Test
    void statusCannotBeSuppliedOnInsertEvenThoughItHasADefault() {
        assertDenied(
                "INSERT INTO organization (name, login_key_normalized, timezone, status)"
                        + " VALUES ('Acme Corp', '"
                        + uniqueLoginKey()
                        + "', 'Asia/Kolkata', 'ACTIVE')");
    }

    @Test
    void createdAtAndUpdatedAtCannotBeSuppliedOnInsertEvenThoughTheyHaveDefaults() {
        assertDenied(
                "INSERT INTO organization (name, login_key_normalized, timezone, created_at)"
                        + " VALUES ('Acme Corp', '"
                        + uniqueLoginKey()
                        + "', 'Asia/Kolkata', now())");
        assertDenied(
                "INSERT INTO organization (name, login_key_normalized, timezone, updated_at)"
                        + " VALUES ('Acme Corp', '"
                        + uniqueLoginKey()
                        + "', 'Asia/Kolkata', now())");
    }

    @Test
    void nameTimezoneStatusOnboardingAndUpdatedAtCanBeUpdated() throws SQLException {
        String key = uniqueLoginKey();
        try (PreparedStatement ps =
                runtime.prepareStatement(
                        "INSERT INTO organization (name, login_key_normalized, timezone)"
                                + " VALUES (?, ?, ?)")) {
            ps.setString(1, "Acme Corp");
            ps.setString(2, key);
            ps.setString(3, "Asia/Kolkata");
            ps.executeUpdate();
        }

        try (PreparedStatement ps =
                runtime.prepareStatement(
                        "UPDATE organization SET name = 'Acme Corp Ltd', timezone = 'UTC',"
                                + " status = 'ACTIVE', onboarding_completed_at = now(),"
                                + " updated_at = now() WHERE login_key_normalized = ?")) {
            ps.setString(1, key);
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }

        try (PreparedStatement ps =
                runtime.prepareStatement(
                        "SELECT name, status FROM organization WHERE login_key_normalized = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("name")).isEqualTo("Acme Corp Ltd");
                assertThat(rs.getString("status")).isEqualTo("ACTIVE");
            }
        }
    }

    @Test
    void loginKeyAndIdCannotBeUpdated() {
        assertDenied("UPDATE organization SET login_key_normalized = 'someone-else'");
        assertDenied("UPDATE organization SET id = gen_random_uuid()");
    }

    @Test
    void deleteAndTruncateAreDenied() throws SQLException {
        String key = uniqueLoginKey();
        try (PreparedStatement ps =
                runtime.prepareStatement(
                        "INSERT INTO organization (name, login_key_normalized, timezone)"
                                + " VALUES (?, ?, ?)")) {
            ps.setString(1, "Acme Corp");
            ps.setString(2, key);
            ps.setString(3, "Asia/Kolkata");
            ps.executeUpdate();
        }

        assertDenied("DELETE FROM organization");
        assertDenied("TRUNCATE organization");
    }

    @Test
    void constraintsApplyToTheRuntimeRoleToo() {
        assertThatThrownBy(
                        () -> {
                            try (Statement s = runtime.createStatement()) {
                                s.execute(
                                        "INSERT INTO organization (name, login_key_normalized,"
                                                + " timezone) VALUES ('', '"
                                                + uniqueLoginKey()
                                                + "', 'Asia/Kolkata')");
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
                                        + " WHERE oid = 'public.organization'::regclass")) {
            while (acl.next()) {
                assertThat(acl.getString(1)).doesNotStartWith("=");
            }
        }
    }
}
