package com.peoplehub.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.SqlErrors;
import com.peoplehub.support.TestOrganizations;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V13 (organization_verification_token): applies cleanly, the table has the approved shape, and its
 * constraints reject bad data (b2-2, Spec 2.1.3, B2-2/5). Runs as the container's superuser; {@code
 * OrganizationVerificationTokenRuntimeRoleTest} covers what the runtime role may do.
 *
 * <p>No {@code @BeforeEach} cleanup: same "no cleanup, unique keys per test" pattern {@code
 * OrganizationMigrationTest} already uses, since every row here needs its own real organization
 * anyway (the FK to organization).
 */
@IntegrationTest
class OrganizationVerificationTokenMigrationTest {

    private static final String INSERT =
            "INSERT INTO organization_verification_token (organization_id, token_hash, expires_at)"
                    + " VALUES (?, ?, ?)";

    @Autowired private JdbcTemplate jdbc;

    private static Timestamp inOneDay() {
        return Timestamp.from(Instant.now().plus(24, ChronoUnit.HOURS));
    }

    private UUID insertRow() {
        UUID org = TestOrganizations.insert(jdbc);
        return jdbc.queryForObject(
                INSERT.replace("VALUES (?, ?, ?)", "VALUES (?, ?, ?) RETURNING id"),
                UUID.class,
                org,
                "hash-" + UUID.randomUUID(),
                inOneDay());
    }

    @Test
    void v13AppliesCleanly() {
        Integer applied =
                jdbc.queryForObject(
                        "SELECT count(*) FROM flyway_schema_history WHERE version = '13' AND"
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
    void columnsHaveTheApprovedTypesAndNullability() {
        record Column(String type, Integer length, String nullable) {}
        Map<String, Column> columns =
                jdbc
                        .queryForList(
                                "SELECT column_name, data_type, character_maximum_length,"
                                        + " is_nullable FROM information_schema.columns"
                                        + " WHERE table_name = 'organization_verification_token'")
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
                        "organization_id",
                        "token_hash",
                        "expires_at",
                        "consumed_at",
                        "created_at");
        assertThat(columns.get("id")).isEqualTo(new Column("uuid", null, "NO"));
        assertThat(columns.get("organization_id")).isEqualTo(new Column("uuid", null, "NO"));
        assertThat(columns.get("token_hash")).isEqualTo(new Column("character varying", 255, "NO"));
        assertThat(columns.get("expires_at"))
                .isEqualTo(new Column("timestamp with time zone", null, "NO"));
        assertThat(columns.get("consumed_at"))
                .isEqualTo(new Column("timestamp with time zone", null, "YES"));
        assertThat(columns.get("created_at"))
                .isEqualTo(new Column("timestamp with time zone", null, "NO"));
    }

    @Test
    void idDefaultsToARandomUuid() {
        String defaultExpression =
                jdbc.queryForObject(
                        "SELECT column_default FROM information_schema.columns WHERE table_name ="
                                + " 'organization_verification_token' AND column_name = 'id'",
                        String.class);

        assertThat(defaultExpression).contains("gen_random_uuid");
    }

    @Test
    void organizationIdMustReferenceARealOrganization() {
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        INSERT,
                                        UUID.randomUUID(),
                                        "hash-" + UUID.randomUUID(),
                                        inOneDay()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(SqlErrors.sqlState(e)).isEqualTo("23503"));
    }

    @Test
    void tokenHashMustBeUnique() {
        UUID org = TestOrganizations.insert(jdbc);
        String hash = "hash-" + UUID.randomUUID();
        jdbc.update(INSERT, org, hash, inOneDay());

        assertThatThrownBy(() -> jdbc.update(INSERT, org, hash, inOneDay()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(SqlErrors.sqlState(e)).isEqualTo("23505"));
    }

    @Test
    void tokenHashCannotBeBlank() {
        UUID org = TestOrganizations.insert(jdbc);

        assertThatThrownBy(() -> jdbc.update(INSERT, org, "", inOneDay()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .message()
                .contains("ck_organization_verification_token_token_hash_not_blank");
    }

    @Test
    void consumedAtDefaultsToNull() {
        UUID id = insertRow();

        assertThat(
                        jdbc.queryForObject(
                                "SELECT consumed_at FROM organization_verification_token WHERE"
                                        + " id = ?",
                                Timestamp.class,
                                id))
                .isNull();
    }

    @Test
    void createdAtDefaultsToTheDatabaseClock() {
        UUID id = insertRow();

        Boolean timed =
                jdbc.queryForObject(
                        "SELECT created_at <= now() FROM organization_verification_token WHERE"
                                + " id = ?",
                        Boolean.class,
                        id);

        assertThat(timed).isTrue();
    }

    @Test
    void thereIsExactlyOneSecondaryIndexOnOrganizationId() {
        var indexes =
                jdbc.queryForList(
                        "SELECT indexname FROM pg_indexes WHERE tablename ="
                                + " 'organization_verification_token'",
                        String.class);

        assertThat(indexes)
                .containsExactlyInAnyOrder(
                        "pk_organization_verification_token",
                        "uq_organization_verification_token_token_hash",
                        "idx_organization_verification_token_organization_id");
    }
}
