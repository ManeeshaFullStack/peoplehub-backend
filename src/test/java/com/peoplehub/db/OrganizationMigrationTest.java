package com.peoplehub.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.SqlErrors;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V8 (organization): applies cleanly, the table has the approved shape, and its constraints reject
 * bad data (b2-1, Spec 2.1.1, 12, 12.1). Mirrors {@code EmailSuppressionMigrationTest}'s coverage
 * of the equivalent V7 migration. Runs as the container's superuser; {@code
 * OrganizationRuntimeRoleTest} covers what the runtime role may do.
 *
 * <p>No {@code @BeforeEach} cleanup: {@code organization} is now referenced by {@code audit_log}
 * (b2-1, V12), which is append-only -- a {@code DELETE FROM organization} can fail on a completely
 * unrelated test class's audit row and there is no way to clear that first. Every test instead uses
 * its own unique login key (the table's own unique key), the same "no cleanup, unique keys per
 * test" pattern {@code AuditLogMigrationTest}/{@code EmailOutboxMigrationTest} already use.
 */
@IntegrationTest
class OrganizationMigrationTest {

    private static final String INSERT =
            "INSERT INTO organization (name, login_key_normalized, timezone) VALUES (?, ?, ?)";

    @Autowired private JdbcTemplate jdbc;

    private static String uniqueLoginKey() {
        return "org-" + UUID.randomUUID();
    }

    private UUID insertRow() {
        return jdbc.queryForObject(
                INSERT.replace("VALUES (?, ?, ?)", "VALUES (?, ?, ?) RETURNING id"),
                UUID.class,
                "Acme Corp",
                uniqueLoginKey(),
                "Asia/Kolkata");
    }

    @Test
    void v8AppliesCleanly() {
        Integer applied =
                jdbc.queryForObject(
                        "SELECT count(*) FROM flyway_schema_history WHERE version = '8' AND success",
                        Integer.class);
        Integer failed =
                jdbc.queryForObject(
                        "SELECT count(*) FROM flyway_schema_history WHERE NOT success",
                        Integer.class);

        assertThat(applied).isEqualTo(1);
        assertThat(failed).isZero();
    }

    @Test
    void columnsHaveTheApprovedTypesAndNullability() {
        record Column(String type, Integer length, String nullable) {}
        Map<String, Column> columns =
                jdbc
                        .queryForList(
                                "SELECT column_name, data_type, character_maximum_length,"
                                        + " is_nullable FROM information_schema.columns"
                                        + " WHERE table_name = 'organization'")
                        .stream()
                        .collect(
                                Collectors.toMap(
                                        r -> (String) r.get("column_name"),
                                        r ->
                                                new Column(
                                                        (String) r.get("data_type"),
                                                        (Integer) r.get("character_maximum_length"),
                                                        (String) r.get("is_nullable"))));

        assertThat(columns.keySet())
                .containsExactlyInAnyOrder(
                        "id",
                        "name",
                        "login_key_normalized",
                        "timezone",
                        "status",
                        "onboarding_completed_at",
                        "created_at",
                        "updated_at",
                        // b2-7 (V19): the organization MFA policy.
                        "mfa_policy");
        assertThat(columns.get("id")).isEqualTo(new Column("uuid", null, "NO"));
        assertThat(columns.get("name")).isEqualTo(new Column("character varying", 200, "NO"));
        assertThat(columns.get("login_key_normalized"))
                .isEqualTo(new Column("character varying", 200, "NO"));
        assertThat(columns.get("timezone")).isEqualTo(new Column("character varying", 64, "NO"));
        assertThat(columns.get("status")).isEqualTo(new Column("character varying", 24, "NO"));
        assertThat(columns.get("mfa_policy")).isEqualTo(new Column("character varying", 32, "NO"));
        assertThat(columns.get("onboarding_completed_at"))
                .isEqualTo(new Column("timestamp with time zone", null, "YES"));
        assertThat(columns.get("created_at"))
                .isEqualTo(new Column("timestamp with time zone", null, "NO"));
        assertThat(columns.get("updated_at"))
                .isEqualTo(new Column("timestamp with time zone", null, "NO"));
    }

    @Test
    void idDefaultsToARandomUuid() {
        String defaultExpression =
                jdbc.queryForObject(
                        "SELECT column_default FROM information_schema.columns"
                                + " WHERE table_name = 'organization' AND column_name = 'id'",
                        String.class);

        assertThat(defaultExpression).contains("gen_random_uuid");
    }

    @Test
    void statusDefaultsToPendingVerification() {
        UUID id = insertRow();

        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM organization WHERE id = ?", String.class, id))
                .isEqualTo("PENDING_VERIFICATION");
    }

    @Test
    void loginKeyMustBeUnique() {
        String key = uniqueLoginKey();
        jdbc.update(INSERT, "Acme Corp", key, "Asia/Kolkata");

        assertThatThrownBy(() -> jdbc.update(INSERT, "Other Org", key, "Asia/Kolkata"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(SqlErrors.sqlState(e)).isEqualTo("23505"));
    }

    @Test
    void nameCannotBeBlank() {
        assertThatThrownBy(() -> jdbc.update(INSERT, "", uniqueLoginKey(), "Asia/Kolkata"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .message()
                .contains("ck_organization_name_not_blank");
    }

    @Test
    void loginKeyCannotBeBlank() {
        assertThatThrownBy(() -> jdbc.update(INSERT, "Acme Corp", "", "Asia/Kolkata"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .message()
                .contains("ck_organization_login_key_not_blank");
    }

    @Test
    void loginKeyMustAlreadyBeLowerCaseAndTrimmed() {
        for (String badKey : new String[] {"Acme-Corp", "acme corp ", " acme-corp", "ACME"}) {
            assertThatThrownBy(() -> jdbc.update(INSERT, "Acme Corp", badKey, "Asia/Kolkata"))
                    .as("login_key_normalized = '" + badKey + "'")
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .message()
                    .contains("ck_organization_login_key_is_normalized");
        }
        // A genuinely normalized key is accepted.
        jdbc.update(INSERT, "Acme Corp", "acme-corp-" + UUID.randomUUID(), "Asia/Kolkata");
    }

    @Test
    void timezoneCannotBeBlank() {
        assertThatThrownBy(() -> jdbc.update(INSERT, "Acme Corp", uniqueLoginKey(), ""))
                .isInstanceOf(DataIntegrityViolationException.class)
                .message()
                .contains("ck_organization_timezone_not_blank");
    }

    @Test
    void statusMustBeOneOfTheFourApprovedValues() {
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO organization (name, login_key_normalized,"
                                                + " timezone, status) VALUES (?, ?, ?, 'BOGUS')",
                                        "Acme Corp",
                                        uniqueLoginKey(),
                                        "Asia/Kolkata"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .message()
                .contains("ck_organization_status");
    }

    @Test
    void aSuppliedIdIsAcceptedFromThisSuperuserConnectionButTheGeneratedDefaultAlsoWorks() {
        // Unlike a GENERATED ALWAYS AS IDENTITY column, id's DEFAULT gen_random_uuid() is not
        // syntax-enforced: this connection (the table owner, every privilege) can still supply one
        // explicitly. The equivalent guarantee for the application is privilege-enforced instead --
        // OrganizationRuntimeRoleTest proves the runtime role specifically has no INSERT on id.
        UUID supplied = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO organization (id, name, login_key_normalized, timezone)"
                        + " VALUES (?, ?, ?, ?)",
                supplied,
                "Acme Corp",
                uniqueLoginKey(),
                "Asia/Kolkata");

        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM organization WHERE id = ?",
                                Integer.class,
                                supplied))
                .isEqualTo(1);
        // The default path (used by insertRow() throughout this class) also works, generating a
        // fresh random id each time.
        UUID generated1 = insertRow();
        UUID generated2 = insertRow();
        assertThat(generated1).isNotNull().isNotEqualTo(generated2);
    }

    @Test
    void createdAtAndUpdatedAtDefaultToTheDatabaseClock() {
        UUID id = insertRow();

        Map<String, Object> row =
                jdbc.queryForMap(
                        "SELECT created_at <= now() AS created_timed,"
                                + " updated_at <= now() AS updated_timed"
                                + " FROM organization WHERE id = ?",
                        id);

        assertThat(row.get("created_timed")).isEqualTo(true);
        assertThat(row.get("updated_timed")).isEqualTo(true);
    }

    @Test
    void thereIsNoSecondaryIndexBeyondThePrimaryKeyAndTheLoginKeyUnique() {
        var indexes =
                jdbc.queryForList(
                        "SELECT indexname FROM pg_indexes WHERE tablename = 'organization'",
                        String.class);

        assertThat(indexes)
                .containsExactlyInAnyOrder("pk_organization", "uq_organization_login_key");
    }
}
