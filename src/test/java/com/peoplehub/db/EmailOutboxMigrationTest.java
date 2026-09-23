package com.peoplehub.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.SqlErrors;
import com.peoplehub.support.TestOrganizations;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V4 (email outbox): applies cleanly, the table has the approved shape, its constraints reject bad
 * data, and -- unlike {@code audit_log} -- it is a plain mutable table with no append-only trigger
 * (b1-1, Spec 9.2). Mirrors {@code AuditLogMigrationTest}'s coverage of the equivalent V3
 * migration. These run as the container's superuser, so every rejection here is a constraint, never
 * a missing privilege; {@code EmailOutboxRuntimeRoleTest} covers what the runtime role may do.
 */
@IntegrationTest
class EmailOutboxMigrationTest {

    private static final String INSERT =
            "INSERT INTO email_outbox (organization_id, recipient, type) VALUES (?, ?, ?)";

    @Autowired private JdbcTemplate jdbc;

    private UUID insertRow() {
        // b2-1 (V12): organization_id now has a real FK to organization(id).
        UUID org = TestOrganizations.insert(jdbc);
        jdbc.update(INSERT, org, "jane@example.com", "SOMETHING_HAPPENED");
        return org;
    }

    private long countFor(UUID org) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM email_outbox WHERE organization_id = ?", Long.class, org);
    }

    // ---- V4 and the table's shape ----

    @Test
    void v4AppliesCleanly() {
        Integer applied =
                jdbc.queryForObject(
                        "SELECT count(*) FROM flyway_schema_history WHERE version = '4' AND success",
                        Integer.class);
        Integer failed =
                jdbc.queryForObject(
                        "SELECT count(*) FROM flyway_schema_history WHERE NOT success",
                        Integer.class);

        assertThat(applied).isEqualTo(1);
        assertThat(failed).isZero();
    }

    @Test
    void columnsHaveTheApprovedTypesLengthsAndNullability() {
        record Column(
                String type, Integer length, String nullable, String isIdentity, String gen) {}
        Map<String, Column> columns =
                jdbc
                        .queryForList(
                                "SELECT column_name, data_type, character_maximum_length,"
                                        + " is_nullable, is_identity, identity_generation"
                                        + " FROM information_schema.columns"
                                        + " WHERE table_name = 'email_outbox'")
                        .stream()
                        .collect(
                                Collectors.toMap(
                                        r -> (String) r.get("column_name"),
                                        r ->
                                                new Column(
                                                        (String) r.get("data_type"),
                                                        (Integer) r.get("character_maximum_length"),
                                                        (String) r.get("is_nullable"),
                                                        (String) r.get("is_identity"),
                                                        (String) r.get("identity_generation"))));

        assertThat(columns.keySet())
                .containsExactlyInAnyOrder(
                        "id",
                        "organization_id",
                        "recipient",
                        "type",
                        "payload",
                        "status",
                        "attempts",
                        "next_attempt_at",
                        "provider_message_id",
                        "error",
                        "last_attempt_at",
                        "created_at");
        assertThat(columns.get("id")).isEqualTo(new Column("bigint", null, "NO", "YES", "ALWAYS"));
        assertThat(columns.get("organization_id"))
                .isEqualTo(new Column("uuid", null, "NO", "NO", null));
        assertThat(columns.get("recipient"))
                .isEqualTo(new Column("character varying", 254, "NO", "NO", null));
        assertThat(columns.get("type"))
                .isEqualTo(new Column("character varying", 64, "NO", "NO", null));
        assertThat(columns.get("payload")).isEqualTo(new Column("jsonb", null, "NO", "NO", null));
        assertThat(columns.get("status"))
                .isEqualTo(new Column("character varying", 16, "NO", "NO", null));
        assertThat(columns.get("attempts"))
                .isEqualTo(new Column("integer", null, "NO", "NO", null));
        assertThat(columns.get("next_attempt_at"))
                .isEqualTo(new Column("timestamp with time zone", null, "YES", "NO", null));
        assertThat(columns.get("provider_message_id"))
                .isEqualTo(new Column("character varying", 255, "YES", "NO", null));
        assertThat(columns.get("error"))
                .isEqualTo(new Column("character varying", 500, "YES", "NO", null));
        assertThat(columns.get("last_attempt_at"))
                .isEqualTo(new Column("timestamp with time zone", null, "YES", "NO", null));
        assertThat(columns.get("created_at"))
                .isEqualTo(new Column("timestamp with time zone", null, "NO", "NO", null));
    }

    @Test
    void createdAtDefaultsToTheDatabaseClock() {
        String defaultExpression =
                jdbc.queryForObject(
                        "SELECT column_default FROM information_schema.columns"
                                + " WHERE table_name = 'email_outbox' AND column_name = 'created_at'",
                        String.class);

        assertThat(defaultExpression).isEqualTo("now()");
    }

    @Test
    void statusDefaultsToPendingAndAttemptsDefaultsToZero() {
        String statusDefault =
                jdbc.queryForObject(
                        "SELECT column_default FROM information_schema.columns"
                                + " WHERE table_name = 'email_outbox' AND column_name = 'status'",
                        String.class);
        String attemptsDefault =
                jdbc.queryForObject(
                        "SELECT column_default FROM information_schema.columns"
                                + " WHERE table_name = 'email_outbox' AND column_name = 'attempts'",
                        String.class);

        assertThat(statusDefault).contains("PENDING");
        assertThat(attemptsDefault).isEqualTo("0");
    }

    @Test
    void hasExactlyOneForeignKeyToOrganizationAddedByV12() {
        // V4 itself created no FK (the organization table did not exist yet, B0-6/1's pattern
        // reapplied for this table); b2-1's V12 added fk_email_outbox_organization once it did.
        // (V4 also added no secondary index -- "indexes wait for the reader" -- but V5 later did:
        // EmailOutboxSendingMigrationTest covers the full, current index inventory.)
        // TenantFkRetrofitMigrationTest exercises the V11-to-V12 transition itself, including the
        // failure case for an orphaned organization_id.
        List<String> foreignKeyNames =
                jdbc.queryForList(
                        "SELECT conname FROM pg_constraint"
                                + " WHERE conrelid = 'email_outbox'::regclass AND contype = 'f'",
                        String.class);

        assertThat(foreignKeyNames).containsExactly("fk_email_outbox_organization");
    }

    // ---- constraints ----

    @Test
    void organizationIdCannotBeNull() {
        assertThatThrownBy(
                        () -> jdbc.update(INSERT, null, "jane@example.com", "SOMETHING_HAPPENED"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(
                        e ->
                                assertThat(SqlErrors.sqlState(e))
                                        .isEqualTo(SqlErrors.NOT_NULL_VIOLATION));
    }

    @Test
    void anOrganizationIdThatDoesNotResolveToARealOrganizationIsRejectedByTheV12ForeignKey() {
        // Before b2-1 (V12), any non-nil UUID satisfied the not-nil CHECK below; now
        // organization_id
        // must resolve to a real organization.id row.
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        INSERT,
                                        UUID.randomUUID(),
                                        "jane@example.com",
                                        "SOMETHING_HAPPENED"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(SqlErrors.sqlState(e)).isEqualTo("23503"));
    }

    @Test
    void theNilOrganizationUuidIsRejected() {
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        INSERT,
                                        new UUID(0L, 0L),
                                        "jane@example.com",
                                        "SOMETHING_HAPPENED"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(
                        e -> assertThat(SqlErrors.sqlState(e)).isEqualTo(SqlErrors.CHECK_VIOLATION))
                .message()
                .contains("ck_email_outbox_organization_not_nil");
    }

    @Test
    void recipientCannotBeBlank() {
        assertThatThrownBy(() -> jdbc.update(INSERT, UUID.randomUUID(), "", "SOMETHING_HAPPENED"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .message()
                .contains("ck_email_outbox_recipient");
    }

    @Test
    void typeMustBeUpperSnakeCase() {
        // A 65-character value fails on the VARCHAR(64) length limit itself, not the CHECK, so only
        // the shorter bad values below assert which constraint fired (mirrors
        // AuditLogMigrationTest.actorActionAndTargetsMustBeInTheirFormat, which makes the same
        // distinction).
        String[] shortAndBad = {"lower_case", "1_STARTS_WITH_DIGIT", "HAS-DASH"};

        for (String type : shortAndBad) {
            assertThatThrownBy(
                            () -> jdbc.update(INSERT, UUID.randomUUID(), "jane@example.com", type))
                    .as("type = '" + type + "'")
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .message()
                    .contains("ck_email_outbox_type");
        }
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        INSERT,
                                        UUID.randomUUID(),
                                        "jane@example.com",
                                        "A".repeat(65)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void statusMustBeOneOfTheFiveApprovedValues() {
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO email_outbox (organization_id, recipient, type,"
                                                + " status) VALUES (?, ?, ?, 'BOGUS')",
                                        UUID.randomUUID(),
                                        "jane@example.com",
                                        "SOMETHING_HAPPENED"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .message()
                .contains("ck_email_outbox_status");
    }

    @Test
    void attemptsCannotBeNegative() {
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO email_outbox (organization_id, recipient, type,"
                                                + " attempts) VALUES (?, ?, ?, -1)",
                                        UUID.randomUUID(),
                                        "jane@example.com",
                                        "SOMETHING_HAPPENED"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .message()
                .contains("ck_email_outbox_attempts");
    }

    @Test
    void payloadMustBeAnObjectOfAtMostFourKilobytes() {
        String oversized = "{\"v\":1,\"x\":\"" + "x".repeat(4100) + "\"}";
        for (String payload : List.of("[]", "\"text\"", "1", "null", oversized)) {
            assertThatThrownBy(
                            () ->
                                    jdbc.update(
                                            "INSERT INTO email_outbox (organization_id, recipient,"
                                                    + " type, payload) VALUES (?, ?, ?, ?::jsonb)",
                                            UUID.randomUUID(),
                                            "jane@example.com",
                                            "SOMETHING_HAPPENED",
                                            payload))
                    .as(payload.substring(0, Math.min(payload.length(), 20)))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Test
    void theRowIdIsAlwaysGeneratedNeverSupplied() {
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO email_outbox (id, organization_id, recipient,"
                                                + " type) VALUES (1, ?, ?, 'SOMETHING_HAPPENED')",
                                        UUID.randomUUID(),
                                        "jane@example.com"))
                .satisfies(e -> assertThat(SqlErrors.sqlMessage(e)).contains("GENERATED ALWAYS"));
    }

    @Test
    void createdAtIsPopulatedByTheDatabase() {
        UUID org = insertRow();

        Boolean fromDatabaseClock =
                jdbc.queryForObject(
                        "SELECT created_at <= now() AND created_at > now() - interval '5 minutes'"
                                + " FROM email_outbox WHERE organization_id = ?",
                        Boolean.class,
                        org);

        assertThat(fromDatabaseClock).isTrue();
    }

    @Test
    void payloadDefaultsToAnEmptyObject() {
        UUID org = insertRow();

        String payload =
                jdbc.queryForObject(
                        "SELECT payload::text FROM email_outbox WHERE organization_id = ?",
                        String.class,
                        org);

        assertThat(payload).isEqualTo("{}");
    }

    // ---- deliberately NOT append-only, unlike audit_log ----

    @Test
    void unlikeAuditLogThisTableHasNoAppendOnlyTriggerAndAllowsUpdateAndDelete() {
        UUID org = insertRow();

        Integer triggers =
                jdbc.queryForObject(
                        "SELECT count(*) FROM pg_trigger WHERE tgrelid ="
                                + " 'email_outbox'::regclass AND NOT tgisinternal",
                        Integer.class);
        assertThat(triggers).isZero();

        jdbc.update("UPDATE email_outbox SET status = 'SENT' WHERE organization_id = ?", org);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM email_outbox WHERE organization_id = ?",
                                String.class,
                                org))
                .isEqualTo("SENT");

        jdbc.update("DELETE FROM email_outbox WHERE organization_id = ?", org);
        assertThat(countFor(org)).isZero();
    }
}
