package com.peoplehub.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.PrivilegedFixture;
import com.peoplehub.support.SqlErrors;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V10 (employee_invitation): applies cleanly, the table has the approved shape, and its constraints
 * -- including the one-active-invitation-per-organization-and-email partial unique index -- reject
 * bad data (b2-1, Spec 2.1.5, 3.3, 12.1). Mirrors {@code EmployeeMigrationTest}'s coverage of the
 * equivalent V9 migration. Runs as the container's superuser; {@code
 * EmployeeInvitationRuntimeRoleTest} covers what the runtime role may do.
 *
 * <p>No {@code @BeforeEach} cleanup: {@code organization} is referenced by the append-only {@code
 * audit_log} (b2-1, V12), so a table-wide {@code DELETE} can fail on an unrelated test class's row.
 * Every test creates its own fresh organization/employee/invitation rows instead.
 */
@IntegrationTest
class EmployeeInvitationMigrationTest {

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

    private UUID insertInviter(UUID org) {
        String email = "inviter-" + UUID.randomUUID() + "@example.com";
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

    private static String uniqueInviteeEmail() {
        return "invitee-" + UUID.randomUUID() + "@example.com";
    }

    private java.sql.Timestamp inOneHour() {
        return java.sql.Timestamp.from(Instant.now().plus(1, ChronoUnit.HOURS));
    }

    @Test
    void v10AppliesCleanly() {
        Integer applied =
                jdbc.queryForObject(
                        "SELECT count(*) FROM flyway_schema_history WHERE version = '10' AND"
                                + " success",
                        Integer.class);
        Integer failed =
                jdbc.queryForObject(
                        "SELECT count(*) FROM flyway_schema_history WHERE NOT success",
                        Integer.class);

        assertThat(applied).isEqualTo(1);
        assertThat(failed).isZero();
    }

    @Test
    void organizationAndInviterMustReferenceRealRows() {
        UUID org = insertOrganization();
        UUID inviter = insertInviter(org);

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO employee_invitation (organization_id,"
                                                + " email_normalized, intended_role, token_hash,"
                                                + " inviter_employee_id, expires_at) VALUES (?, ?,"
                                                + " 'EMPLOYEE', 'hash-' || gen_random_uuid(), ?, ?)",
                                        UUID.randomUUID(),
                                        uniqueInviteeEmail(),
                                        inviter,
                                        inOneHour()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(SqlErrors.sqlState(e)).isEqualTo("23503"));

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO employee_invitation (organization_id,"
                                                + " email_normalized, intended_role, token_hash,"
                                                + " inviter_employee_id, expires_at) VALUES (?, ?,"
                                                + " 'EMPLOYEE', 'hash-' || gen_random_uuid(), ?, ?)",
                                        org,
                                        uniqueInviteeEmail(),
                                        UUID.randomUUID(),
                                        inOneHour()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(SqlErrors.sqlState(e)).isEqualTo("23503"));
    }

    @Test
    void intendedRoleCanNeverBeSuperAdmin() {
        UUID org = insertOrganization();
        UUID inviter = insertInviter(org);

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO employee_invitation (organization_id,"
                                                + " email_normalized, intended_role, token_hash,"
                                                + " inviter_employee_id, expires_at) VALUES (?, ?,"
                                                + " 'SUPER_ADMIN', 'hash-' || gen_random_uuid(), ?, ?)",
                                        org,
                                        uniqueInviteeEmail(),
                                        inviter,
                                        inOneHour()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .message()
                .contains("ck_employee_invitation_intended_role");
    }

    @Test
    void emailNormalizedMustAlreadyBeLowerCaseAndTrimmed() {
        UUID org = insertOrganization();
        UUID inviter = insertInviter(org);

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO employee_invitation (organization_id,"
                                                + " email_normalized, intended_role, token_hash,"
                                                + " inviter_employee_id, expires_at) VALUES (?,"
                                                + " 'Invitee@Example.com', 'EMPLOYEE', 'hash-' || gen_random_uuid(), ?,"
                                                + " ?)",
                                        org,
                                        inviter,
                                        inOneHour()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .message()
                .contains("ck_employee_invitation_email_normalized_is_normalized");
    }

    @Test
    void onlyOneActiveInvitationPerOrganizationAndEmailAtATime() {
        UUID org = insertOrganization();
        UUID inviter = insertInviter(org);
        String email = uniqueInviteeEmail();
        String insert =
                "INSERT INTO employee_invitation (organization_id, email_normalized,"
                        + " intended_role, token_hash, inviter_employee_id, expires_at)"
                        + " VALUES (?, ?, 'EMPLOYEE', ?, ?, ?)";
        jdbc.update(insert, org, email, "hash-" + UUID.randomUUID(), inviter, inOneHour());

        // A second active invite to the same org+email is rejected.
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        insert,
                                        org,
                                        email,
                                        "hash-" + UUID.randomUUID(),
                                        inviter,
                                        inOneHour()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(SqlErrors.sqlState(e)).isEqualTo("23505"));

        // Once the first is revoked, a new one is allowed.
        jdbc.update(
                "UPDATE employee_invitation SET revoked_at = now()"
                        + " WHERE organization_id = ? AND email_normalized = ?",
                org,
                email);
        jdbc.update(insert, org, email, "hash-" + UUID.randomUUID(), inviter, inOneHour());

        // Different organization, same email: allowed (tenant-scoped).
        UUID org2 = insertOrganization();
        UUID inviter2 = insertInviter(org2);
        jdbc.update(insert, org2, email, "hash-" + UUID.randomUUID(), inviter2, inOneHour());
    }

    @Test
    void consumedInvitationDoesNotCountAsActiveEither() {
        UUID org = insertOrganization();
        UUID inviter = insertInviter(org);
        String email = uniqueInviteeEmail();
        jdbc.update(
                "INSERT INTO employee_invitation (organization_id, email_normalized,"
                        + " intended_role, token_hash, inviter_employee_id, expires_at)"
                        + " VALUES (?, ?, 'EMPLOYEE', 'hash-' || gen_random_uuid(), ?, ?)",
                org,
                email,
                inviter,
                inOneHour());
        jdbc.update(
                "UPDATE employee_invitation SET consumed_at = now()"
                        + " WHERE organization_id = ? AND email_normalized = ?",
                org,
                email);

        jdbc.update(
                "INSERT INTO employee_invitation (organization_id, email_normalized,"
                        + " intended_role, token_hash, inviter_employee_id, expires_at)"
                        + " VALUES (?, ?, 'EMPLOYEE', 'hash-' || gen_random_uuid(), ?, ?)",
                org,
                email,
                inviter,
                inOneHour());
    }

    @Test
    void theRowIdCreatedAtAreDatabaseGeneratedFromTheDefault() {
        UUID org = insertOrganization();
        UUID inviter = insertInviter(org);

        UUID id =
                jdbc.queryForObject(
                        "INSERT INTO employee_invitation (organization_id, email_normalized,"
                                + " intended_role, token_hash, inviter_employee_id, expires_at)"
                                + " VALUES (?, ?, 'EMPLOYEE', 'hash-' || gen_random_uuid(), ?, ?) RETURNING id",
                        UUID.class,
                        org,
                        uniqueInviteeEmail(),
                        inviter,
                        inOneHour());

        var row =
                jdbc.queryForMap(
                        "SELECT created_at <= now() AS timed FROM employee_invitation"
                                + " WHERE id = ?",
                        id);
        assertThat(row.get("timed")).isEqualTo(true);
    }

    @Test
    void tokenHashIsUniqueAndThePartialUniqueOnActiveInvitesExists() {
        var indexes =
                jdbc.queryForList(
                        "SELECT indexname FROM pg_indexes WHERE tablename = 'employee_invitation'",
                        String.class);

        assertThat(indexes)
                .containsExactlyInAnyOrder(
                        "pk_employee_invitation",
                        "uq_employee_invitation_active_per_org_email",
                        // V15 (b2-4) replaced V10's plain token_hash index with a unique
                        // constraint.
                        "uq_employee_invitation_token_hash");
    }
}
