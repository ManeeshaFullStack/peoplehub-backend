package com.peoplehub.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.PrivilegedFixture;
import com.peoplehub.support.SqlErrors;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V19 (b2-7, B2-7/1, B2-7/5, B2-7/6, B2-7/27): the organization MFA policy, the per-employee MFA
 * state and the three new refresh-token revoke reasons. Runs as the container's superuser; {@code
 * MfaPolicyRuntimeRoleTest} covers the runtime role. Every test creates its own rows.
 */
@IntegrationTest
class MfaPolicyMigrationTest {

    private static final List<String> POLICIES =
            List.of(
                    "DISABLED",
                    "OPTIONAL",
                    "REQUIRED_FOR_ADMINS",
                    "REQUIRED_FOR_SELECTED_USERS",
                    "REQUIRED_FOR_ALL");

    private static final List<String> REVOKE_REASONS =
            List.of(
                    "ROTATED",
                    "LOGOUT",
                    "REUSE_DETECTED",
                    "PASSWORD_RESET",
                    "PASSWORD_CHANGED",
                    "SESSION_REVOKED",
                    "DEACTIVATED",
                    "MFA_REQUIRED",
                    "MFA_RESET",
                    "ROLE_CHANGED");

    @Autowired @PrivilegedFixture private JdbcTemplate jdbc;

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

    private UUID insertToken(UUID org, UUID employee) {
        return jdbc.queryForObject(
                "INSERT INTO refresh_token (organization_id, employee_id, token_hash, family_id,"
                        + " expires_at, absolute_expires_at) VALUES (?, ?, ?, gen_random_uuid(),"
                        + " now() + interval '30 days', now() + interval '90 days') RETURNING id",
                UUID.class,
                org,
                employee,
                "hash-" + UUID.randomUUID());
    }

    private void assertCheckViolation(Runnable statement, String constraint) {
        assertThatThrownBy(statement::run)
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(
                        e -> assertThat(SqlErrors.sqlState(e)).isEqualTo(SqlErrors.CHECK_VIOLATION))
                .message()
                .contains(constraint);
    }

    private Map<String, Object> column(String table, String column) {
        return jdbc.queryForMap(
                "SELECT data_type, is_nullable, column_default FROM information_schema.columns"
                        + " WHERE table_name = ? AND column_name = ?",
                table,
                column);
    }

    @Test
    void v19AppliesCleanly() {
        Integer applied =
                jdbc.queryForObject(
                        "SELECT count(*) FROM flyway_schema_history WHERE version = '19' AND"
                                + " success",
                        Integer.class);

        assertThat(applied).isEqualTo(1);
    }

    // ---------------------------------------------------------------------------------------
    // organization.mfa_policy
    // ---------------------------------------------------------------------------------------

    @Test
    void theMfaPolicyColumnIsRequiredAndDefaultsToDisabled() {
        assertThat(column("organization", "mfa_policy"))
                .containsEntry("data_type", "character varying")
                .containsEntry("is_nullable", "NO")
                .containsEntry("column_default", "'DISABLED'::character varying");
    }

    @Test
    void aNewOrganizationStartsWithMfaDisabled() {
        UUID org = insertOrganization();

        assertThat(
                        jdbc.queryForObject(
                                "SELECT mfa_policy FROM organization WHERE id = ?",
                                String.class,
                                org))
                .isEqualTo("DISABLED");
    }

    @Test
    void everyPolicyValueIsAccepted() {
        UUID org = insertOrganization();

        for (String policy : POLICIES) {
            assertThat(
                            jdbc.update(
                                    "UPDATE organization SET mfa_policy = ? WHERE id = ?",
                                    policy,
                                    org))
                    .as(policy)
                    .isEqualTo(1);
        }
    }

    @Test
    void anUnknownPolicyIsRejected() {
        UUID org = insertOrganization();

        for (String policy : List.of("REQUIRED", "optional", "ENABLED", "")) {
            assertCheckViolation(
                    () ->
                            jdbc.update(
                                    "UPDATE organization SET mfa_policy = ? WHERE id = ?",
                                    policy,
                                    org),
                    "ck_organization_mfa_policy");
        }
    }

    @Test
    void thePolicyCheckListsExactlyTheFivePolicies() {
        assertThat(checkValues("ck_organization_mfa_policy"))
                .containsExactlyInAnyOrderElementsOf(POLICIES);
    }

    // ---------------------------------------------------------------------------------------
    // employee MFA state
    // ---------------------------------------------------------------------------------------

    @Test
    void theEmployeeMfaColumnsHaveTheApprovedTypesAndNullability() {
        assertThat(column("employee", "mfa_required"))
                .containsEntry("data_type", "boolean")
                .containsEntry("is_nullable", "NO")
                .containsEntry("column_default", "false");
        assertThat(column("employee", "mfa_enrolled_at"))
                .containsEntry("data_type", "timestamp with time zone")
                .containsEntry("is_nullable", "YES");
        assertThat(column("employee", "mfa_totp_last_step"))
                .containsEntry("data_type", "bigint")
                .containsEntry("is_nullable", "YES");
        assertThat(column("employee", "mfa_reminder_dismissed_at"))
                .containsEntry("data_type", "timestamp with time zone")
                .containsEntry("is_nullable", "YES");
    }

    @Test
    void aNewEmployeeIsNotEnrolledNotSelectedAndHasNoMfaState() {
        UUID employee = insertEmployee(insertOrganization());

        Map<String, Object> row =
                jdbc.queryForMap(
                        "SELECT mfa_enabled, mfa_totp_secret, mfa_required, mfa_enrolled_at,"
                                + " mfa_totp_last_step, mfa_reminder_dismissed_at"
                                + " FROM employee WHERE id = ?",
                        employee);

        assertThat(row.get("mfa_enabled")).isEqualTo(false);
        assertThat(row.get("mfa_required")).isEqualTo(false);
        assertThat(row.get("mfa_totp_secret")).isNull();
        assertThat(row.get("mfa_enrolled_at")).isNull();
        assertThat(row.get("mfa_totp_last_step")).isNull();
        assertThat(row.get("mfa_reminder_dismissed_at")).isNull();
    }

    @Test
    void aPendingEnrollmentIsAllowed() {
        UUID employee = insertEmployee(insertOrganization());

        assertThat(
                        jdbc.update(
                                "UPDATE employee SET mfa_totp_secret = 'k1:ciphertext' WHERE id = ?",
                                employee))
                .isEqualTo(1);
    }

    @Test
    void aConfirmedEnrollmentCanBeRecordedAndThenCleared() {
        UUID employee = insertEmployee(insertOrganization());

        assertThat(
                        jdbc.update(
                                "UPDATE employee SET mfa_enabled = true,"
                                        + " mfa_totp_secret = 'k1:ciphertext', mfa_enrolled_at = now()"
                                        + " WHERE id = ?",
                                employee))
                .isEqualTo(1);
        // An MFA reset or disable clears all three together.
        assertThat(
                        jdbc.update(
                                "UPDATE employee SET mfa_enabled = false, mfa_totp_secret = NULL,"
                                        + " mfa_enrolled_at = NULL WHERE id = ?",
                                employee))
                .isEqualTo(1);
    }

    @Test
    void mfaCannotBeEnabledWithoutASecret() {
        UUID employee = insertEmployee(insertOrganization());

        assertCheckViolation(
                () ->
                        jdbc.update(
                                "UPDATE employee SET mfa_enabled = true, mfa_enrolled_at = now()"
                                        + " WHERE id = ?",
                                employee),
                "ck_employee_mfa_state_consistent");
    }

    @Test
    void mfaCannotBeEnabledWithoutAnEnrollmentTime() {
        UUID employee = insertEmployee(insertOrganization());

        assertCheckViolation(
                () ->
                        jdbc.update(
                                "UPDATE employee SET mfa_enabled = true,"
                                        + " mfa_totp_secret = 'k1:ciphertext' WHERE id = ?",
                                employee),
                "ck_employee_mfa_state_consistent");
    }

    @Test
    void anEnrollmentTimeRequiresMfaToBeEnabled() {
        UUID employee = insertEmployee(insertOrganization());

        assertCheckViolation(
                () ->
                        jdbc.update(
                                "UPDATE employee SET mfa_totp_secret = 'k1:ciphertext',"
                                        + " mfa_enrolled_at = now() WHERE id = ?",
                                employee),
                "ck_employee_mfa_state_consistent");
    }

    @Test
    void theLastTotpStepCanNeverBeNegative() {
        UUID employee = insertEmployee(insertOrganization());

        assertCheckViolation(
                () ->
                        jdbc.update(
                                "UPDATE employee SET mfa_totp_last_step = -1 WHERE id = ?",
                                employee),
                "ck_employee_mfa_totp_last_step_not_negative");
    }

    // ---------------------------------------------------------------------------------------
    // refresh_token.revoke_reason
    // ---------------------------------------------------------------------------------------

    @Test
    void theRevokeReasonCheckListsExactlyTheTenReasons() {
        assertThat(checkValues("ck_refresh_token_revoke_reason"))
                .containsExactlyInAnyOrderElementsOf(REVOKE_REASONS);
    }

    @Test
    void theThreeNewReasonsAreAccepted() {
        UUID org = insertOrganization();
        UUID employee = insertEmployee(org);

        for (String reason : List.of("MFA_REQUIRED", "MFA_RESET", "ROLE_CHANGED")) {
            UUID token = insertToken(org, employee);
            assertThat(
                            jdbc.update(
                                    "UPDATE refresh_token SET revoked = true, revoked_at = now(),"
                                            + " revoke_reason = ? WHERE id = ?",
                                    reason,
                                    token))
                    .as(reason)
                    .isEqualTo(1);
        }
    }

    @Test
    void anUnknownReasonIsStillRejected() {
        UUID org = insertOrganization();
        UUID token = insertToken(org, insertEmployee(org));

        for (String reason : List.of("MFA", "mfa_reset", "PROMOTED", "")) {
            assertCheckViolation(
                    () ->
                            jdbc.update(
                                    "UPDATE refresh_token SET revoked = true, revoked_at = now(),"
                                            + " revoke_reason = ? WHERE id = ?",
                                    reason,
                                    token),
                    "ck_refresh_token_revoke_reason");
        }
    }

    // ---------------------------------------------------------------------------------------
    // Documentation
    // ---------------------------------------------------------------------------------------

    @Test
    void theNewColumnsAreDocumented() {
        for (String[] tableColumn :
                new String[][] {
                    {"organization", "mfa_policy"},
                    {"employee", "mfa_required"},
                    {"employee", "mfa_enrolled_at"},
                    {"employee", "mfa_totp_last_step"},
                    {"employee", "mfa_reminder_dismissed_at"}
                }) {
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT col_description(?::regclass, ordinal_position)"
                                            + " FROM information_schema.columns"
                                            + " WHERE table_name = ? AND column_name = ?",
                                    String.class,
                                    tableColumn[0],
                                    tableColumn[0],
                                    tableColumn[1]))
                    .as(tableColumn[0] + "." + tableColumn[1])
                    .contains("b2-7 (V19)");
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT col_description('refresh_token'::regclass, ordinal_position)"
                                        + " FROM information_schema.columns"
                                        + " WHERE table_name = 'refresh_token'"
                                        + " AND column_name = 'revoke_reason'",
                                String.class))
                .contains("MFA_REQUIRED")
                .contains("MFA_RESET")
                .contains("ROLE_CHANGED");
    }

    private List<String> checkValues(String constraint) {
        String definition =
                jdbc.queryForObject(
                        "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ?",
                        String.class,
                        constraint);
        return Pattern.compile("'([^']*)'")
                .matcher(definition)
                .results()
                .map(match -> match.group(1))
                .toList();
    }
}
