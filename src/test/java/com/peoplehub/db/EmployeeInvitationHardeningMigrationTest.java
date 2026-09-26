package com.peoplehub.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.PrivilegedFixture;
import com.peoplehub.support.SqlErrors;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V15 (employee_invitation hardening, b2-4, B2-4/O6): a token answers to one invitation only, an
 * invitation is never both consumed and revoked, and its inviter belongs to its organization. Runs
 * as the container's superuser; every test creates its own rows with unique values.
 */
@IntegrationTest
class EmployeeInvitationHardeningMigrationTest {

    private static final String INSERT =
            "INSERT INTO employee_invitation (organization_id, email_normalized, intended_role,"
                    + " token_hash, inviter_employee_id, expires_at)"
                    + " VALUES (?, ?, 'EMPLOYEE', ?, ?, ?) RETURNING id";

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
        String email = "admin-" + UUID.randomUUID() + "@example.com";
        return jdbc.queryForObject(
                "INSERT INTO employee (organization_id, employee_code, name, email,"
                        + " email_normalized, role) VALUES (?, ?, 'Jane Admin', ?, ?, 'ADMIN')"
                        + " RETURNING id",
                UUID.class,
                org,
                "E-" + UUID.randomUUID(),
                email,
                email);
    }

    private static String inviteeEmail() {
        return "invitee-" + UUID.randomUUID() + "@example.com";
    }

    private static Timestamp inSevenDays() {
        return Timestamp.from(Instant.now().plus(7, ChronoUnit.DAYS));
    }

    private UUID insertInvitation(UUID org, UUID inviter, String tokenHash) {
        return jdbc.queryForObject(
                INSERT, UUID.class, org, inviteeEmail(), tokenHash, inviter, inSevenDays());
    }

    @Test
    void v15AppliesCleanly() {
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM flyway_schema_history WHERE version = '15'"
                                        + " AND success",
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void aTokenHashCanBelongToOneInvitationOnly() {
        UUID org = insertOrganization();
        UUID inviter = insertEmployee(org);
        String hash = "hash-" + UUID.randomUUID();
        insertInvitation(org, inviter, hash);

        // Even in another organization.
        UUID otherOrg = insertOrganization();
        UUID otherInviter = insertEmployee(otherOrg);
        assertThatThrownBy(() -> insertInvitation(otherOrg, otherInviter, hash))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(SqlErrors.sqlState(e)).isEqualTo("23505"))
                .message()
                .contains("uq_employee_invitation_token_hash");
    }

    @Test
    void anInvitationIsNeverBothConsumedAndRevoked() {
        UUID org = insertOrganization();
        UUID inviter = insertEmployee(org);
        UUID consumed = insertInvitation(org, inviter, "hash-" + UUID.randomUUID());
        jdbc.update("UPDATE employee_invitation SET consumed_at = now() WHERE id = ?", consumed);

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE employee_invitation SET revoked_at = now()"
                                                + " WHERE id = ?",
                                        consumed))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(
                        e -> assertThat(SqlErrors.sqlState(e)).isEqualTo(SqlErrors.CHECK_VIOLATION))
                .message()
                .contains("ck_employee_invitation_not_consumed_and_revoked");

        UUID revoked = insertInvitation(org, inviter, "hash-" + UUID.randomUUID());
        jdbc.update("UPDATE employee_invitation SET revoked_at = now() WHERE id = ?", revoked);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE employee_invitation SET consumed_at = now()"
                                                + " WHERE id = ?",
                                        revoked))
                .isInstanceOf(DataIntegrityViolationException.class)
                .message()
                .contains("ck_employee_invitation_not_consumed_and_revoked");
    }

    @Test
    void theInviterMustBelongToTheInvitationsOrganization() {
        UUID org = insertOrganization();
        UUID otherOrg = insertOrganization();
        UUID inviterInOtherOrg = insertEmployee(otherOrg);

        assertThatThrownBy(
                        () -> insertInvitation(org, inviterInOtherOrg, "hash-" + UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(SqlErrors.sqlState(e)).isEqualTo("23503"))
                .message()
                .contains("fk_employee_invitation_inviter_in_organization");
    }

    @Test
    void thePlainTokenHashIndexWasReplacedByTheUniqueConstraint() {
        assertThat(
                        jdbc.queryForList(
                                "SELECT indexname FROM pg_indexes"
                                        + " WHERE tablename = 'employee_invitation'",
                                String.class))
                .contains("uq_employee_invitation_token_hash")
                .doesNotContain("idx_employee_invitation_token_hash");
    }
}
