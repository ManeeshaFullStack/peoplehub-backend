package com.peoplehub.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.PrivilegedFixture;
import com.peoplehub.support.SqlErrors;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V23 (b2-7, B2-7/6): {@code employee.mfa_totp_pending_secret}, where a step-up re-enrollment's new
 * secret waits until it is confirmed. Runs as the container's superuser; {@code
 * MfaPendingSecretRuntimeRoleTest} covers the runtime role. Every test creates its own rows.
 */
@IntegrationTest
class MfaPendingSecretMigrationTest {

    @Autowired @PrivilegedFixture private JdbcTemplate jdbc;

    private UUID insertEmployee() {
        UUID org =
                jdbc.queryForObject(
                        "INSERT INTO organization (name, login_key_normalized, timezone)"
                                + " VALUES (?, ?, ?) RETURNING id",
                        UUID.class,
                        "Acme Corp",
                        "org-" + UUID.randomUUID(),
                        "Asia/Kolkata");
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

    private void enroll(UUID employee) {
        jdbc.update(
                "UPDATE employee SET mfa_enabled = true, mfa_totp_secret = 'k1:active',"
                        + " mfa_enrolled_at = now() WHERE id = ?",
                employee);
    }

    @Test
    void v23AppliesCleanly() {
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM flyway_schema_history WHERE version = '23'"
                                        + " AND success",
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void theColumnIsAnOptionalStringLikeTheActiveSecret() {
        Map<String, Object> pending =
                jdbc.queryForMap(
                        "SELECT data_type, character_maximum_length, is_nullable, column_default"
                                + " FROM information_schema.columns WHERE table_name = 'employee'"
                                + " AND column_name = 'mfa_totp_pending_secret'");
        Map<String, Object> active =
                jdbc.queryForMap(
                        "SELECT data_type, character_maximum_length FROM information_schema.columns"
                                + " WHERE table_name = 'employee' AND column_name = 'mfa_totp_secret'");

        assertThat(pending.get("data_type")).isEqualTo(active.get("data_type"));
        assertThat(pending.get("character_maximum_length"))
                .isEqualTo(active.get("character_maximum_length"));
        assertThat(pending.get("is_nullable")).isEqualTo("YES");
        assertThat(pending.get("column_default")).isNull();
    }

    @Test
    void aNewEmployeeHasNoPendingSecret() {
        UUID employee = insertEmployee();

        assertThat(
                        jdbc.queryForObject(
                                "SELECT mfa_totp_pending_secret FROM employee WHERE id = ?",
                                String.class,
                                employee))
                .isNull();
    }

    @Test
    void anEnrolledPersonCanHoldAPendingSecretNextToTheActiveOne() {
        UUID employee = insertEmployee();
        enroll(employee);

        jdbc.update(
                "UPDATE employee SET mfa_totp_pending_secret = 'k1:pending' WHERE id = ?",
                employee);

        assertThat(
                        jdbc.queryForMap(
                                "SELECT mfa_totp_secret, mfa_totp_pending_secret FROM employee"
                                        + " WHERE id = ?",
                                employee))
                .containsEntry("mfa_totp_secret", "k1:active")
                .containsEntry("mfa_totp_pending_secret", "k1:pending");
    }

    @Test
    void thePendingSecretCanBePromotedAndCleared() {
        UUID employee = insertEmployee();
        enroll(employee);
        jdbc.update(
                "UPDATE employee SET mfa_totp_pending_secret = 'k1:pending' WHERE id = ?",
                employee);

        jdbc.update(
                "UPDATE employee SET mfa_totp_secret = mfa_totp_pending_secret,"
                        + " mfa_totp_pending_secret = NULL WHERE id = ?",
                employee);

        assertThat(
                        jdbc.queryForMap(
                                "SELECT mfa_totp_secret, mfa_totp_pending_secret FROM employee"
                                        + " WHERE id = ?",
                                employee))
                .containsEntry("mfa_totp_secret", "k1:pending")
                .containsEntry("mfa_totp_pending_secret", null);
    }

    @Test
    void aPendingSecretNeedsAnActiveEnrollment() {
        UUID employee = insertEmployee();

        assertCheckViolation(
                () ->
                        jdbc.update(
                                "UPDATE employee SET mfa_totp_pending_secret = 'k1:pending'"
                                        + " WHERE id = ?",
                                employee));
    }

    @Test
    void mfaCannotBeDisabledWhileASecretIsPending() {
        UUID employee = insertEmployee();
        enroll(employee);
        jdbc.update(
                "UPDATE employee SET mfa_totp_pending_secret = 'k1:pending' WHERE id = ?",
                employee);

        assertCheckViolation(
                () ->
                        jdbc.update(
                                "UPDATE employee SET mfa_enabled = false, mfa_enrolled_at = NULL"
                                        + " WHERE id = ?",
                                employee));
    }

    @Test
    void theColumnAndConstraintAreDocumented() {
        assertThat(
                        jdbc.queryForObject(
                                "SELECT col_description('employee'::regclass, attnum)"
                                        + " FROM pg_attribute WHERE attrelid = 'employee'::regclass"
                                        + " AND attname = 'mfa_totp_pending_secret'",
                                String.class))
                .contains("V23")
                .contains("never plaintext");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM pg_constraint"
                                        + " WHERE conname = 'ck_employee_mfa_pending_secret_enrolled'",
                                Integer.class))
                .isEqualTo(1);
    }

    private void assertCheckViolation(Runnable statement) {
        assertThatThrownBy(statement::run)
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(
                        e -> assertThat(SqlErrors.sqlState(e)).isEqualTo(SqlErrors.CHECK_VIOLATION))
                .message()
                .contains("ck_employee_mfa_pending_secret_enrolled");
    }
}
