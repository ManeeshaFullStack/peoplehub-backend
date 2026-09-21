package com.peoplehub.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.peoplehub.common.api.correlation.CorrelationId;
import com.peoplehub.common.logging.ActorId;
import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.SqlErrors;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V3 (audit log): applies cleanly, the table has the approved shape, its constraints reject bad
 * data, and the append-only trigger rejects UPDATE, DELETE and TRUNCATE (B0-6). These run as the
 * container's superuser, so every rejection here is the trigger or a constraint, never a missing
 * privilege; {@code AuditLogRuntimeRoleTest} covers what the runtime role may do.
 */
@IntegrationTest
class AuditLogMigrationTest {

    private static final String INSERT =
            "INSERT INTO audit_log (organization_id, actor_id, action) VALUES (?, ?, ?)";
    private static final String TRIGGER_MESSAGE = "audit_log is append-only";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;

    private UUID insertRow() {
        UUID org = UUID.randomUUID();
        jdbc.update(INSERT, org, "job:test", "SOMETHING_HAPPENED");
        return org;
    }

    private long countFor(UUID org) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE organization_id = ?", Long.class, org);
    }

    // ---- V3 and the table's shape ----

    @Test
    void v3AppliesCleanly() {
        Integer applied =
                jdbc.queryForObject(
                        "SELECT count(*) FROM flyway_schema_history WHERE version = '3' AND success",
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
                                        + " WHERE table_name = 'audit_log'")
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
                        "actor_id",
                        "action",
                        "target_type",
                        "target_id",
                        "occurred_at",
                        "ip",
                        "correlation_id",
                        "details");
        assertThat(columns.get("id")).isEqualTo(new Column("bigint", null, "NO", "YES", "ALWAYS"));
        assertThat(columns.get("organization_id"))
                .isEqualTo(new Column("uuid", null, "NO", "NO", null));
        assertThat(columns.get("actor_id"))
                .isEqualTo(new Column("character varying", 64, "NO", "NO", null));
        assertThat(columns.get("action"))
                .isEqualTo(new Column("character varying", 64, "NO", "NO", null));
        assertThat(columns.get("target_type"))
                .isEqualTo(new Column("character varying", 64, "YES", "NO", null));
        assertThat(columns.get("target_id"))
                .isEqualTo(new Column("character varying", 64, "YES", "NO", null));
        assertThat(columns.get("occurred_at"))
                .isEqualTo(new Column("timestamp with time zone", null, "NO", "NO", null));
        assertThat(columns.get("ip")).isEqualTo(new Column("inet", null, "YES", "NO", null));
        assertThat(columns.get("correlation_id"))
                .isEqualTo(new Column("character varying", 64, "YES", "NO", null));
        assertThat(columns.get("details")).isEqualTo(new Column("jsonb", null, "NO", "NO", null));
    }

    @Test
    void occurredAtDefaultsToTheDatabaseClock() {
        String defaultExpression =
                jdbc.queryForObject(
                        "SELECT column_default FROM information_schema.columns"
                                + " WHERE table_name = 'audit_log' AND column_name = 'occurred_at'",
                        String.class);

        assertThat(defaultExpression).isEqualTo("now()");
    }

    @Test
    void thereIsNoOrganizationForeignKeyAndNoSecondaryIndex() {
        // The organization table arrives with B2, which adds the FK. Indexes wait for the reader.
        Integer foreignKeys =
                jdbc.queryForObject(
                        "SELECT count(*) FROM pg_constraint"
                                + " WHERE conrelid = 'audit_log'::regclass AND contype = 'f'",
                        Integer.class);
        List<String> indexes =
                jdbc.queryForList(
                        "SELECT indexname FROM pg_indexes WHERE tablename = 'audit_log'",
                        String.class);

        assertThat(foreignKeys).isZero();
        assertThat(indexes).containsExactly("pk_audit_log");
    }

    // ---- constraints ----

    @Test
    void organizationIdCannotBeNull() {
        assertThatThrownBy(() -> jdbc.update(INSERT, null, "job:test", "SOMETHING_HAPPENED"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(
                        e ->
                                assertThat(SqlErrors.sqlState(e))
                                        .isEqualTo(SqlErrors.NOT_NULL_VIOLATION));
    }

    @Test
    void theNilOrganizationUuidIsRejected() {
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        INSERT, new UUID(0L, 0L), "job:test", "SOMETHING_HAPPENED"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(
                        e -> assertThat(SqlErrors.sqlState(e)).isEqualTo(SqlErrors.CHECK_VIOLATION))
                .message()
                .contains("ck_audit_log_organization_not_nil");
    }

    @Test
    void actorActionAndTargetsMustBeInTheirFormat() {
        UUID org = UUID.randomUUID();
        String[][] bad = {
            {"actor_id", "has space"},
            {"actor_id", "person@example.com"},
            {"actor_id", "new\nline"},
            {"actor_id", ""},
            {"actor_id", "a".repeat(65)},
            {"action", "lower_case"},
            {"action", "1_STARTS_WITH_DIGIT"},
            {"action", "HAS-DASH"},
            {"action", "A".repeat(65)},
            {"target_type", "lower"},
            {"target_type", "HAS SPACE"},
            {"target_id", "person@example.com"},
            {"target_id", "Jane Doe"},
            {"correlation_id", "has space"},
            {"correlation_id", "under:colon"},
            {"correlation_id", "c".repeat(65)}
        };

        for (String[] column : bad) {
            String sql = insertWith(column[0]);
            assertThatThrownBy(() -> jdbc.update(sql, org, column[1]))
                    .as(column[0] + " = '" + column[1].replace("\n", "\\n") + "'")
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThat(countFor(org)).isZero();
    }

    /** An insert of valid values for everything except the one column, which is the parameter. */
    private static String insertWith(String column) {
        String actor = column.equals("actor_id") ? "?" : "'job:test'";
        String action = column.equals("action") ? "?" : "'SOMETHING_HAPPENED'";
        String targetType =
                column.equals("target_type") || column.equals("target_id")
                        ? (column.equals("target_type") ? "?" : "'EMPLOYEE'")
                        : "NULL";
        String targetId = column.equals("target_id") ? "?" : "NULL";
        String correlation = column.equals("correlation_id") ? "?" : "NULL";
        return "INSERT INTO audit_log (organization_id, actor_id, action, target_type, target_id,"
                + " correlation_id) SELECT ?, "
                + actor
                + ", "
                + action
                + ", "
                + targetType
                + ", "
                + targetId
                + ", "
                + correlation;
    }

    @Test
    void aTargetIdNeedsATargetType() {
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO audit_log (organization_id, actor_id, action,"
                                                + " target_id) VALUES (?, 'job:test',"
                                                + " 'SOMETHING_HAPPENED', 'e-1')",
                                        UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .message()
                .contains("ck_audit_log_target_id");
    }

    @Test
    void detailsMustBeAnObjectOfAtMostFourKilobytes() {
        String oversized = "{\"v\":1,\"x\":\"" + "x".repeat(4100) + "\"}";
        for (String details : List.of("[]", "\"text\"", "1", "null", oversized)) {
            assertThatThrownBy(
                            () ->
                                    jdbc.update(
                                            "INSERT INTO audit_log (organization_id, actor_id,"
                                                    + " action, details) VALUES (?, 'job:test',"
                                                    + " 'SOMETHING_HAPPENED', ?::jsonb)",
                                            UUID.randomUUID(),
                                            details))
                    .as(details.substring(0, Math.min(details.length(), 20)))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Test
    void theRowIdIsAlwaysGeneratedNeverSupplied() {
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO audit_log (id, organization_id, actor_id,"
                                                + " action) VALUES (1, ?, 'job:test',"
                                                + " 'SOMETHING_HAPPENED')",
                                        UUID.randomUUID()))
                .satisfies(e -> assertThat(SqlErrors.sqlMessage(e)).contains("GENERATED ALWAYS"));
    }

    @Test
    void occurredAtIsPopulatedByTheDatabase() {
        UUID org = insertRow();

        Boolean fromDatabaseClock =
                jdbc.queryForObject(
                        "SELECT occurred_at <= now() AND occurred_at > now() - interval '5 minutes'"
                                + " FROM audit_log WHERE organization_id = ?",
                        Boolean.class,
                        org);

        assertThat(fromDatabaseClock).isTrue();
    }

    @Test
    void detailsDefaultsToAnEmptyObject() {
        UUID org = insertRow();

        String details =
                jdbc.queryForObject(
                        "SELECT details::text FROM audit_log WHERE organization_id = ?",
                        String.class,
                        org);

        assertThat(details).isEqualTo("{}");
    }

    // ---- ActorId / CorrelationId compatibility ----

    @Test
    void actorIdsMeanTheSameToJavaAndTheDatabase() {
        List<String> corpus =
                List.of(
                        ActorId.ANONYMOUS,
                        ActorId.SYSTEM,
                        "job:daily-close",
                        "job:x.y_z",
                        UUID.randomUUID().toString(),
                        "a",
                        "a".repeat(64),
                        "a".repeat(65),
                        "",
                        "has space",
                        "new\nline",
                        "trailing\n",
                        "person@example.com",
                        "semi;colon",
                        "a/b",
                        "ünï");

        for (String candidate : corpus) {
            boolean java = acceptedByActorId(candidate);
            boolean database = acceptedByDatabase("actor_id", candidate);
            assertThat(database)
                    .as("actor id '" + candidate.replace("\n", "\\n") + "'")
                    .isEqualTo(java);
        }
        // Sanity: the corpus really has both outcomes.
        assertThat(acceptedByActorId("job:daily-close")).isTrue();
        assertThat(acceptedByActorId("has space")).isFalse();
    }

    @Test
    void correlationIdsMeanTheSameToJavaAndTheDatabase() {
        List<String> corpus =
                List.of(
                        CorrelationId.generate(),
                        "req-1",
                        "a.b_c-d",
                        "a".repeat(64),
                        "a".repeat(65),
                        "",
                        "has space",
                        "new\nline",
                        "trailing\n",
                        "under:colon",
                        "ünï");

        for (String candidate : corpus) {
            assertThat(acceptedByDatabase("correlation_id", candidate))
                    .as("correlation id '" + candidate.replace("\n", "\\n") + "'")
                    .isEqualTo(CorrelationId.isValid(candidate));
        }
    }

    private static boolean acceptedByActorId(String candidate) {
        try {
            ActorId.set(candidate);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        } finally {
            ActorId.clear();
        }
    }

    private boolean acceptedByDatabase(String column, String value) {
        try {
            jdbc.update(insertWith(column), UUID.randomUUID(), value);
            return true;
        } catch (DataIntegrityViolationException e) {
            return false;
        }
    }

    // ---- append-only ----

    @Test
    void theTriggerExistsIsStatementLevelAndCoversUpdateDeleteTruncate() {
        Map<String, Object> trigger =
                jdbc.queryForMap(
                        "SELECT tgtype::int AS tgtype, tgenabled::text AS tgenabled"
                                + " FROM pg_trigger WHERE tgrelid = 'audit_log'::regclass"
                                + " AND tgname = 'trg_audit_log_append_only'");
        int tgtype = (Integer) trigger.get("tgtype");

        // pg_trigger.tgtype bits: 1 = row level, 2 = BEFORE, 4 = INSERT, 8 = DELETE, 16 = UPDATE,
        // 32 = TRUNCATE
        assertThat(tgtype & 1).as("row level").isZero();
        assertThat(tgtype & 2).as("BEFORE").isEqualTo(2);
        assertThat(tgtype & 4).as("INSERT is not blocked").isZero();
        assertThat(tgtype & 8).as("DELETE").isEqualTo(8);
        assertThat(tgtype & 16).as("UPDATE").isEqualTo(16);
        assertThat(tgtype & 32).as("TRUNCATE").isEqualTo(32);
        // 'A' = ENABLE ALWAYS (fires whatever session_replication_role says); 'O' would be the
        // default.
        assertThat(trigger.get("tgenabled")).isEqualTo("A");
    }

    @Test
    void auditTableRejectsUpdateAndDelete() {
        // The B0 exit criterion: "audit table rejects UPDATE/DELETE".
        UUID org = insertRow();

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE audit_log SET action = 'TAMPERED' WHERE organization_id = ?",
                                        org))
                .satisfies(
                        e ->
                                assertThat(SqlErrors.sqlState(e))
                                        .isEqualTo(SqlErrors.RAISED_EXCEPTION))
                .satisfies(
                        e ->
                                assertThat(SqlErrors.sqlMessage(e))
                                        .contains(TRIGGER_MESSAGE + ": UPDATE"));
        assertThatThrownBy(
                        () -> jdbc.update("DELETE FROM audit_log WHERE organization_id = ?", org))
                .satisfies(
                        e ->
                                assertThat(SqlErrors.sqlState(e))
                                        .isEqualTo(SqlErrors.RAISED_EXCEPTION))
                .satisfies(
                        e ->
                                assertThat(SqlErrors.sqlMessage(e))
                                        .contains(TRIGGER_MESSAGE + ": DELETE"));

        // Nothing changed.
        assertThat(countFor(org)).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT action FROM audit_log WHERE organization_id = ?",
                                String.class,
                                org))
                .isEqualTo("SOMETHING_HAPPENED");
    }

    @Test
    void anUpdateOrDeleteThatMatchesNoRowsIsRejectedToo() {
        // Statement-level: the operation itself is refused, not just the rows it would touch.
        assertThatThrownBy(() -> jdbc.update("UPDATE audit_log SET action = 'X' WHERE false"))
                .satisfies(e -> assertThat(SqlErrors.sqlMessage(e)).contains(TRIGGER_MESSAGE));
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_log WHERE false"))
                .satisfies(e -> assertThat(SqlErrors.sqlMessage(e)).contains(TRIGGER_MESSAGE));
    }

    @Test
    void truncateIsRejected() {
        UUID org = insertRow();

        assertThatThrownBy(() -> jdbc.execute("TRUNCATE audit_log"))
                .satisfies(
                        e ->
                                assertThat(SqlErrors.sqlState(e))
                                        .isEqualTo(SqlErrors.RAISED_EXCEPTION))
                .satisfies(
                        e ->
                                assertThat(SqlErrors.sqlMessage(e))
                                        .contains(TRIGGER_MESSAGE + ": TRUNCATE"));
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE audit_log RESTART IDENTITY CASCADE"))
                .satisfies(e -> assertThat(SqlErrors.sqlMessage(e)).contains(TRIGGER_MESSAGE));

        assertThat(countFor(org)).isEqualTo(1);
    }

    @Test
    void theTriggerMessageCarriesNoRowData() {
        UUID org = UUID.randomUUID();
        jdbc.update(INSERT, org, "job:secret-looking-actor", "SOMETHING_HAPPENED");

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE audit_log SET action = 'TAMPERED' WHERE organization_id = ?",
                                        org))
                .satisfies(
                        e ->
                                assertThat(SqlErrors.sqlMessage(e))
                                        .doesNotContain(org.toString())
                                        .doesNotContain("secret-looking-actor"));
    }

    @Test
    void enableAlwaysKeepsTheTriggerFiringWhenReplicationRoleSkipsOrdinaryTriggers()
            throws Exception {
        // Negative control first: an ordinary (ENABLE ORIGIN) trigger IS skipped under
        // session_replication_role = replica. That is the bypass ENABLE ALWAYS closes.
        jdbc.execute("DROP TABLE IF EXISTS scratch_ordinary_trigger");
        jdbc.execute("CREATE TABLE scratch_ordinary_trigger (v int)");
        jdbc.execute("INSERT INTO scratch_ordinary_trigger VALUES (1)");
        jdbc.execute(
                "CREATE TRIGGER trg_scratch BEFORE UPDATE ON scratch_ordinary_trigger"
                        + " FOR EACH STATEMENT EXECUTE FUNCTION audit_log_reject_change()");
        UUID org = insertRow();

        try (Connection c = dataSource.getConnection();
                Statement s = c.createStatement()) {
            s.execute("SET session_replication_role = replica");
            try {
                s.executeUpdate("UPDATE scratch_ordinary_trigger SET v = 2");
                // The ordinary trigger did not fire: the control shows the bypass is real.

                assertThatThrownBy(
                                () -> s.executeUpdate("UPDATE audit_log SET action = 'TAMPERED'"))
                        .satisfies(
                                e ->
                                        assertThat(SqlErrors.sqlMessage(e))
                                                .contains(TRIGGER_MESSAGE + ": UPDATE"));
                assertThatThrownBy(() -> s.executeUpdate("DELETE FROM audit_log"))
                        .satisfies(
                                e ->
                                        assertThat(SqlErrors.sqlMessage(e))
                                                .contains(TRIGGER_MESSAGE + ": DELETE"));
                assertThatThrownBy(() -> s.execute("TRUNCATE audit_log"))
                        .satisfies(
                                e ->
                                        assertThat(SqlErrors.sqlMessage(e))
                                                .contains(TRIGGER_MESSAGE + ": TRUNCATE"));
            } finally {
                s.execute("RESET session_replication_role");
                s.execute("DROP TABLE scratch_ordinary_trigger");
            }
        }

        assertThat(countFor(org)).isEqualTo(1);
    }
}
