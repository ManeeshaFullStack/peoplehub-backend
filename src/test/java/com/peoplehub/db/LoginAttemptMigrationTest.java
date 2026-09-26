package com.peoplehub.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.PrivilegedFixture;
import com.peoplehub.support.SqlErrors;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V11 (login_attempt): applies cleanly, the table has the approved shape, has NO organization/
 * employee foreign key at all (B0-6/16's rule: no tenant, no fabricated reference), and its
 * append-only trigger -- reused from {@code audit_log}'s own {@code audit_log_reject_change()}
 * function -- rejects UPDATE, DELETE and TRUNCATE on THIS table too, not only the one it was
 * written for (b2-1, Spec 8.2, 15). Mirrors {@code AuditLogMigrationTest}'s equivalent coverage of
 * V3. Runs as the container's superuser; {@code LoginAttemptRuntimeRoleTest} covers what the
 * runtime role may do.
 *
 * <p>No {@code @BeforeEach} cleanup, matching {@code AuditLogMigrationTest}: the table is
 * append-only, so {@code DELETE} is rejected for every connection including this superuser one --
 * each test uses its own unique {@code organization_login_key_attempted} instead (the same reason
 * {@code AuditLogMigrationTest} scopes its own assertions by a fresh random organization id per
 * test rather than clearing the table).
 */
@IntegrationTest
class LoginAttemptMigrationTest {

    private static final String INSERT =
            "INSERT INTO login_attempt (organization_login_key_attempted, email_attempted, success)"
                    + " VALUES (?, ?, ?)";
    private static final String TRIGGER_MESSAGE = "audit_log is append-only";

    @Autowired @PrivilegedFixture private JdbcTemplate jdbc;

    private String uniqueKey() {
        return "org-" + UUID.randomUUID();
    }

    private long countFor(String key) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM login_attempt WHERE organization_login_key_attempted = ?",
                Long.class,
                key);
    }

    private String insertRow() {
        String key = uniqueKey();
        jdbc.update(INSERT, key, "jane@example.com", false);
        return key;
    }

    @Test
    void v11AppliesCleanly() {
        Integer applied =
                jdbc.queryForObject(
                        "SELECT count(*) FROM flyway_schema_history WHERE version = '11' AND"
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
    void thereIsNoForeignKeyOfAnyKind() {
        Integer foreignKeys =
                jdbc.queryForObject(
                        "SELECT count(*) FROM pg_constraint"
                                + " WHERE conrelid = 'login_attempt'::regclass AND contype = 'f'",
                        Integer.class);

        assertThat(foreignKeys).isZero();
    }

    @Test
    void organizationLoginKeyAndEmailCannotBeBlank() {
        assertThatThrownBy(() -> jdbc.update(INSERT, "", "jane@example.com", false))
                .isInstanceOf(DataIntegrityViolationException.class)
                .message()
                .contains("ck_login_attempt_org_login_key_not_blank");
        assertThatThrownBy(() -> jdbc.update(INSERT, uniqueKey(), "", false))
                .isInstanceOf(DataIntegrityViolationException.class)
                .message()
                .contains("ck_login_attempt_email_not_blank");
    }

    @Test
    void occurredAtIsPopulatedByTheDatabase() {
        String key = insertRow();

        Boolean fromDatabaseClock =
                jdbc.queryForObject(
                        "SELECT occurred_at <= now() AND occurred_at > now() - interval '5 minutes'"
                                + " FROM login_attempt WHERE organization_login_key_attempted = ?",
                        Boolean.class,
                        key);

        assertThat(fromDatabaseClock).isTrue();
    }

    @Test
    void theRowIdIsAlwaysGeneratedNeverSupplied() {
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO login_attempt (id,"
                                                + " organization_login_key_attempted,"
                                                + " email_attempted, success) VALUES (1, ?, ?,"
                                                + " false)",
                                        uniqueKey(),
                                        "jane@example.com"))
                .satisfies(e -> assertThat(SqlErrors.sqlMessage(e)).contains("GENERATED ALWAYS"));
    }

    // ---- append-only, reusing audit_log's trigger function ----

    @Test
    void loginAttemptRejectsUpdateDeleteAndTruncateTooNotJustAuditLog() {
        // Rule 5 from CLAUDE.md B2 development rules: prove the reused trigger function actually
        // protects the SECOND table it is attached to, not only audit_log (the one it was written
        // for). A regression here would mean login_attempt silently lost its append-only guarantee
        // even though audit_log's own tests still pass.
        String key = insertRow();

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE login_attempt SET success = true"
                                                + " WHERE organization_login_key_attempted = ?",
                                        key))
                .satisfies(
                        e ->
                                assertThat(SqlErrors.sqlState(e))
                                        .isEqualTo(SqlErrors.RAISED_EXCEPTION))
                .satisfies(
                        e ->
                                assertThat(SqlErrors.sqlMessage(e))
                                        .contains(TRIGGER_MESSAGE + ": UPDATE"));
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "DELETE FROM login_attempt"
                                                + " WHERE organization_login_key_attempted = ?",
                                        key))
                .satisfies(
                        e ->
                                assertThat(SqlErrors.sqlState(e))
                                        .isEqualTo(SqlErrors.RAISED_EXCEPTION))
                .satisfies(
                        e ->
                                assertThat(SqlErrors.sqlMessage(e))
                                        .contains(TRIGGER_MESSAGE + ": DELETE"));
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE login_attempt"))
                .satisfies(
                        e ->
                                assertThat(SqlErrors.sqlState(e))
                                        .isEqualTo(SqlErrors.RAISED_EXCEPTION))
                .satisfies(
                        e ->
                                assertThat(SqlErrors.sqlMessage(e))
                                        .contains(TRIGGER_MESSAGE + ": TRUNCATE"));

        assertThat(countFor(key)).isEqualTo(1);
    }

    @Test
    void theTriggerIsStatementLevelAndEnableAlways() {
        var trigger =
                jdbc.queryForMap(
                        "SELECT tgtype::int AS tgtype, tgenabled::text AS tgenabled"
                                + " FROM pg_trigger WHERE tgrelid = 'login_attempt'::regclass"
                                + " AND tgname = 'trg_login_attempt_append_only'");
        int tgtype = (Integer) trigger.get("tgtype");

        assertThat(tgtype & 1).as("row level").isZero();
        assertThat(tgtype & 8).as("DELETE").isEqualTo(8);
        assertThat(tgtype & 16).as("UPDATE").isEqualTo(16);
        assertThat(tgtype & 32).as("TRUNCATE").isEqualTo(32);
        assertThat(trigger.get("tgenabled")).isEqualTo("A");
    }

    @Test
    void indexesExistForOrganizationKeyAndIp() {
        var indexes =
                jdbc.queryForList(
                        "SELECT indexname FROM pg_indexes WHERE tablename = 'login_attempt'",
                        String.class);

        assertThat(indexes)
                .containsExactlyInAnyOrder(
                        "pk_login_attempt",
                        "idx_login_attempt_org_key_occurred",
                        "idx_login_attempt_ip_occurred");
    }
}
