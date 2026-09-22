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
 * What the least-privileged runtime role can and cannot do to {@code notification} and {@code
 * notification_preference} (b1-3, Spec 9, 12), against real PostgreSQL and connected as that role.
 * Mirrors {@code EmailOutboxRuntimeRoleTest}'s coverage of the equivalent b1-1/b1-2 tables.
 */
@IntegrationTest
class NotificationRuntimeRoleTest {

    private static final String INSERT_NOTIFICATION =
            "INSERT INTO notification (organization_id, employee_id, type) VALUES (?, ?, 'SOMETHING_HAPPENED')";

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

    private int insertNotification(UUID org, UUID employee) throws SQLException {
        try (PreparedStatement ps = runtime.prepareStatement(INSERT_NOTIFICATION)) {
            ps.setObject(1, org);
            ps.setObject(2, employee);
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

    // ---- notification ----

    @Test
    void notificationInsertAndSelectSucceed() throws SQLException {
        UUID org = UUID.randomUUID();
        UUID employee = UUID.randomUUID();
        assertThat(insertNotification(org, employee)).isEqualTo(1);

        try (PreparedStatement ps =
                runtime.prepareStatement(
                        "SELECT id, read, created_at FROM notification WHERE organization_id = ?")) {
            ps.setObject(1, org);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong("id")).isPositive();
                assertThat(rs.getBoolean("read")).isFalse();
                assertThat(rs.getTimestamp("created_at")).isNotNull();
            }
        }
    }

    @Test
    void notificationReadCanBeUpdatedButNoOtherColumn() throws SQLException {
        UUID org = UUID.randomUUID();
        insertNotification(org, UUID.randomUUID());

        try (PreparedStatement ps =
                runtime.prepareStatement(
                        "UPDATE notification SET read = true WHERE organization_id = ?")) {
            ps.setObject(1, org);
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }

        assertDenied("UPDATE notification SET type = 'SOMETHING_ELSE'");
        assertDenied("UPDATE notification SET organization_id = gen_random_uuid()");
        assertDenied("UPDATE notification SET employee_id = gen_random_uuid()");
        assertDenied("UPDATE notification SET payload = '{}'::jsonb");
        assertDenied("UPDATE notification SET created_at = now()");
    }

    @Test
    void notificationInsertIsDeniedOnIdCreatedAtAndReadEvenThoughTheyHaveDefaults() {
        String org = UUID.randomUUID().toString();
        String employee = UUID.randomUUID().toString();

        assertDenied(
                "INSERT INTO notification (organization_id, employee_id, type, read) VALUES ('"
                        + org
                        + "', '"
                        + employee
                        + "', 'X', true)");
        assertDenied(
                "INSERT INTO notification (organization_id, employee_id, type, created_at) VALUES ('"
                        + org
                        + "', '"
                        + employee
                        + "', 'X', now())");
    }

    @Test
    void notificationDeleteAndTruncateAreDenied() throws SQLException {
        UUID org = UUID.randomUUID();
        insertNotification(org, UUID.randomUUID());

        assertDenied("DELETE FROM notification");
        assertDenied("TRUNCATE notification");
    }

    // ---- notification_preference ----

    @Test
    void preferenceInsertSelectAndUpdateSucceed() throws SQLException {
        UUID org = UUID.randomUUID();
        UUID employee = UUID.randomUUID();
        try (PreparedStatement ps =
                runtime.prepareStatement(
                        "INSERT INTO notification_preference (organization_id, employee_id, type,"
                                + " email, in_app) VALUES (?, ?, 'ORDINARY_TYPE', true, true)")) {
            ps.setObject(1, org);
            ps.setObject(2, employee);
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }

        try (PreparedStatement ps =
                runtime.prepareStatement(
                        "UPDATE notification_preference SET email = false"
                                + " WHERE organization_id = ? AND employee_id = ? AND type = 'ORDINARY_TYPE'")) {
            ps.setObject(1, org);
            ps.setObject(2, employee);
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }

        try (PreparedStatement ps =
                runtime.prepareStatement(
                        "SELECT email, in_app FROM notification_preference"
                                + " WHERE organization_id = ? AND employee_id = ?")) {
            ps.setObject(1, org);
            ps.setObject(2, employee);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean("email")).isFalse();
                assertThat(rs.getBoolean("in_app")).isTrue();
            }
        }
    }

    @Test
    void preferenceKeyColumnsCannotBeUpdated() {
        assertDenied("UPDATE notification_preference SET organization_id = gen_random_uuid()");
        assertDenied("UPDATE notification_preference SET employee_id = gen_random_uuid()");
        assertDenied("UPDATE notification_preference SET type = 'SOMETHING_ELSE'");
    }

    @Test
    void preferenceDeleteAndTruncateAreDenied() throws SQLException {
        UUID org = UUID.randomUUID();
        UUID employee = UUID.randomUUID();
        try (PreparedStatement ps =
                runtime.prepareStatement(
                        "INSERT INTO notification_preference (organization_id, employee_id, type)"
                                + " VALUES (?, ?, 'ORDINARY_TYPE')")) {
            ps.setObject(1, org);
            ps.setObject(2, employee);
            ps.executeUpdate();
        }

        assertDenied("DELETE FROM notification_preference");
        assertDenied("TRUNCATE notification_preference");
    }

    @Test
    void constraintsApplyToTheRuntimeRoleTooForBothTables() {
        assertThatThrownBy(
                        () -> {
                            try (Statement s = runtime.createStatement()) {
                                s.execute(
                                        "INSERT INTO notification (organization_id, employee_id, type)"
                                                + " VALUES (NULL, gen_random_uuid(), 'X')");
                            }
                        })
                .satisfies(
                        e ->
                                assertThat(SqlErrors.sqlState(e))
                                        .isEqualTo(SqlErrors.NOT_NULL_VIOLATION));
    }
}
