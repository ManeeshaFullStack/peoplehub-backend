package com.peoplehub.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.PrivilegedFixture;
import com.peoplehub.support.SqlErrors;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V21 (mfa_challenge, b2-7, B2-7/9, B2-7/10): applies cleanly, has the approved shape, and its
 * constraints reject bad data. Runs as the container's superuser; {@code
 * MfaChallengeRuntimeRoleTest} covers the runtime role. Every test creates its own rows.
 */
@IntegrationTest
class MfaChallengeMigrationTest {

    private static final String INSERT =
            "INSERT INTO mfa_challenge (organization_id, employee_id, token_hash, purpose,"
                    + " device_label, ip, expires_at)"
                    + " VALUES (?, ?, ?, ?, 'Chrome on Windows', '203.0.113.7'::inet,"
                    + " now() + interval '5 minutes') RETURNING id";

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

    private UUID insertChallenge(UUID org, UUID employee, String tokenHash, String purpose) {
        return jdbc.queryForObject(INSERT, UUID.class, org, employee, tokenHash, purpose);
    }

    private UUID insertChallenge() {
        UUID org = insertOrganization();
        return insertChallenge(org, insertEmployee(org), "hash-" + UUID.randomUUID(), "CHALLENGE");
    }

    private void assertCheckViolation(Runnable statement, String constraint) {
        assertThatThrownBy(statement::run)
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(
                        e -> assertThat(SqlErrors.sqlState(e)).isEqualTo(SqlErrors.CHECK_VIOLATION))
                .message()
                .contains(constraint);
    }

    @Test
    void v21AppliesCleanly() {
        Integer applied =
                jdbc.queryForObject(
                        "SELECT count(*) FROM flyway_schema_history WHERE version = '21' AND"
                                + " success",
                        Integer.class);

        assertThat(applied).isEqualTo(1);
    }

    @Test
    void theTableHasTheApprovedColumns() {
        List<Map<String, Object>> columns =
                jdbc.queryForList(
                        "SELECT column_name, data_type, is_nullable FROM information_schema.columns"
                                + " WHERE table_name = 'mfa_challenge' ORDER BY ordinal_position");

        assertThat(columns)
                .extracting(
                        c ->
                                c.get("column_name")
                                        + " "
                                        + c.get("data_type")
                                        + " "
                                        + c.get("is_nullable"))
                .containsExactly(
                        "id uuid NO",
                        "organization_id uuid NO",
                        "employee_id uuid NO",
                        "token_hash character varying NO",
                        "purpose character varying NO",
                        "device_label character varying YES",
                        "ip inet YES",
                        "expires_at timestamp with time zone NO",
                        "failed_attempts integer NO",
                        "consumed_at timestamp with time zone YES",
                        "invalidated_at timestamp with time zone YES",
                        "created_at timestamp with time zone NO");
    }

    @Test
    void aNewChallengeIsUnusedWithNoFailuresAndADatabaseCreationTime() {
        UUID id = insertChallenge();

        Map<String, Object> row =
                jdbc.queryForMap(
                        "SELECT failed_attempts, consumed_at, invalidated_at,"
                                + " created_at > now() - interval '1 minute' AS recent"
                                + " FROM mfa_challenge WHERE id = ?",
                        id);

        assertThat(row.get("failed_attempts")).isEqualTo(0);
        assertThat(row.get("consumed_at")).isNull();
        assertThat(row.get("invalidated_at")).isNull();
        assertThat(row.get("recent")).isEqualTo(true);
    }

    @Test
    void bothPurposesAreAcceptedAndNothingElse() {
        UUID org = insertOrganization();
        UUID employee = insertEmployee(org);

        for (String purpose : List.of("CHALLENGE", "ENROLL")) {
            assertThat(insertChallenge(org, employee, "hash-" + UUID.randomUUID(), purpose))
                    .as(purpose)
                    .isNotNull();
        }
        for (String purpose : List.of("enroll", "STEP_UP", "")) {
            assertCheckViolation(
                    () -> insertChallenge(org, employee, "hash-" + UUID.randomUUID(), purpose),
                    "ck_mfa_challenge_purpose");
        }
    }

    @Test
    void theTokenHashIsUniqueAndNeverBlank() {
        UUID org = insertOrganization();
        UUID employee = insertEmployee(org);
        String hash = "hash-" + UUID.randomUUID();
        insertChallenge(org, employee, hash, "CHALLENGE");

        assertThatThrownBy(() -> insertChallenge(org, employee, hash, "CHALLENGE"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(SqlErrors.sqlState(e)).isEqualTo("23505"));
        assertCheckViolation(
                () -> insertChallenge(org, employee, "", "CHALLENGE"),
                "ck_mfa_challenge_token_hash_not_blank");
    }

    @Test
    void aChallengeCannotBelongToAnEmployeeOfAnotherOrganization() {
        UUID org = insertOrganization();
        UUID employeeOfAnother = insertEmployee(insertOrganization());

        assertThatThrownBy(
                        () ->
                                insertChallenge(
                                        org,
                                        employeeOfAnother,
                                        "hash-" + UUID.randomUUID(),
                                        "CHALLENGE"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(SqlErrors.sqlState(e)).isEqualTo("23503"))
                .message()
                .contains("fk_mfa_challenge_employee_in_organization");
    }

    @Test
    void failedAttemptsCanNeverBeNegative() {
        UUID id = insertChallenge();

        assertCheckViolation(
                () -> jdbc.update("UPDATE mfa_challenge SET failed_attempts = -1 WHERE id = ?", id),
                "ck_mfa_challenge_failed_attempts_not_negative");
    }

    @Test
    void aChallengeIsConsumedOrInvalidatedButNotBoth() {
        UUID consumed = insertChallenge();

        assertThat(
                        jdbc.update(
                                "UPDATE mfa_challenge SET consumed_at = now() WHERE id = ?",
                                consumed))
                .isEqualTo(1);
        assertCheckViolation(
                () ->
                        jdbc.update(
                                "UPDATE mfa_challenge SET invalidated_at = now() WHERE id = ?",
                                consumed),
                "ck_mfa_challenge_not_consumed_and_invalidated");
    }

    @Test
    void theOpenChallengeIndexIsPartial() {
        List<Map<String, Object>> indexes =
                jdbc.queryForList(
                        "SELECT indexname, indexdef FROM pg_indexes"
                                + " WHERE tablename = 'mfa_challenge'");

        assertThat(indexes)
                .extracting(i -> (String) i.get("indexname"))
                .containsExactlyInAnyOrder(
                        "pk_mfa_challenge",
                        "uq_mfa_challenge_token_hash",
                        "idx_mfa_challenge_employee_open");
        assertThat(
                        indexes.stream()
                                .filter(
                                        i ->
                                                "idx_mfa_challenge_employee_open"
                                                        .equals(i.get("indexname")))
                                .map(i -> (String) i.get("indexdef"))
                                .findFirst()
                                .orElseThrow())
                .contains("(organization_id, employee_id)")
                .contains("consumed_at IS NULL")
                .contains("invalidated_at IS NULL");
    }

    @Test
    void theTableIsDocumented() {
        assertThat(
                        jdbc.queryForObject(
                                "SELECT obj_description('mfa_challenge'::regclass, 'pg_class')",
                                String.class))
                .contains("b2-7 (V21)");
    }
}
