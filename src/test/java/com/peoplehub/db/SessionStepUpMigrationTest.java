package com.peoplehub.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.SqlErrors;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V22 (session_step_up, b2-7, B2-7/15, B2-7/16): applies cleanly, has the approved shape, and its
 * constraints reject bad data. Runs as the container's superuser; {@code
 * SessionStepUpRuntimeRoleTest} covers the runtime role. Every test creates its own rows.
 */
@IntegrationTest
class SessionStepUpMigrationTest {

    private static final String INSERT =
            "INSERT INTO session_step_up (organization_id, employee_id, session_id, method)"
                    + " VALUES (?, ?, ?, ?) RETURNING id";

    @Autowired private JdbcTemplate jdbc;

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

    @Test
    void v22AppliesCleanly() {
        Integer applied =
                jdbc.queryForObject(
                        "SELECT count(*) FROM flyway_schema_history WHERE version = '22' AND"
                                + " success",
                        Integer.class);

        assertThat(applied).isEqualTo(1);
    }

    @Test
    void theTableHasTheApprovedColumns() {
        List<Map<String, Object>> columns =
                jdbc.queryForList(
                        "SELECT column_name, data_type, is_nullable FROM information_schema.columns"
                                + " WHERE table_name = 'session_step_up' ORDER BY ordinal_position");

        assertThat(columns)
                .extracting(
                        c ->
                                c.get("column_name")
                                        + " "
                                        + c.get("data_type")
                                        + " "
                                        + c.get("is_nullable"))
                .containsExactly(
                        "id bigint NO",
                        "organization_id uuid NO",
                        "employee_id uuid NO",
                        "session_id uuid NO",
                        "method character varying NO",
                        "verified_at timestamp with time zone NO");
    }

    @Test
    void verifiedAtIsTheDatabasesTime() {
        UUID org = insertOrganization();
        Long id =
                jdbc.queryForObject(
                        INSERT,
                        Long.class,
                        org,
                        insertEmployee(org),
                        UUID.randomUUID(),
                        "PASSWORD");

        assertThat(
                        jdbc.queryForObject(
                                "SELECT verified_at > now() - interval '1 minute'"
                                        + " FROM session_step_up WHERE id = ?",
                                Boolean.class,
                                id))
                .isTrue();
    }

    @Test
    void theThreeMethodsAreAcceptedAndNothingElse() {
        UUID org = insertOrganization();
        UUID employee = insertEmployee(org);
        UUID session = UUID.randomUUID();

        for (String method :
                List.of("PASSWORD", "PASSWORD_AND_TOTP", "PASSWORD_AND_RECOVERY_CODE")) {
            assertThat(jdbc.queryForObject(INSERT, Long.class, org, employee, session, method))
                    .as(method)
                    .isNotNull();
        }
        for (String method : List.of("TOTP", "password", "")) {
            assertThatThrownBy(
                            () ->
                                    jdbc.queryForObject(
                                            INSERT, Long.class, org, employee, session, method))
                    .as(method)
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .satisfies(
                            e ->
                                    assertThat(SqlErrors.sqlState(e))
                                            .isEqualTo(SqlErrors.CHECK_VIOLATION))
                    .message()
                    .contains("ck_session_step_up_method");
        }
    }

    @Test
    void aStepUpCannotBelongToAnEmployeeOfAnotherOrganization() {
        UUID org = insertOrganization();
        UUID employeeOfAnother = insertEmployee(insertOrganization());

        assertThatThrownBy(
                        () ->
                                jdbc.queryForObject(
                                        INSERT,
                                        Long.class,
                                        org,
                                        employeeOfAnother,
                                        UUID.randomUUID(),
                                        "PASSWORD"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(SqlErrors.sqlState(e)).isEqualTo("23503"))
                .message()
                .contains("fk_session_step_up_employee_in_organization");
    }

    @Test
    void theRowIdIsAlwaysGeneratedNeverSupplied() {
        UUID org = insertOrganization();
        UUID employee = insertEmployee(org);

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO session_step_up (id, organization_id,"
                                                + " employee_id, session_id, method)"
                                                + " VALUES (1, ?, ?, ?, 'PASSWORD')",
                                        org,
                                        employee,
                                        UUID.randomUUID()))
                .satisfies(e -> assertThat(SqlErrors.sqlMessage(e)).contains("GENERATED ALWAYS"));
    }

    @Test
    void theSessionLookupIndexExists() {
        List<Map<String, Object>> indexes =
                jdbc.queryForList(
                        "SELECT indexname, indexdef FROM pg_indexes"
                                + " WHERE tablename = 'session_step_up'");

        assertThat(indexes)
                .extracting(i -> (String) i.get("indexname"))
                .containsExactlyInAnyOrder("pk_session_step_up", "idx_session_step_up_session");
        assertThat(
                        indexes.stream()
                                .filter(
                                        i ->
                                                "idx_session_step_up_session"
                                                        .equals(i.get("indexname")))
                                .map(i -> (String) i.get("indexdef"))
                                .findFirst()
                                .orElseThrow())
                .contains("(organization_id, session_id, verified_at)");
    }

    @Test
    void theTableIsDocumented() {
        assertThat(
                        jdbc.queryForObject(
                                "SELECT obj_description('session_step_up'::regclass, 'pg_class')",
                                String.class))
                .contains("b2-7 (V22)");
    }
}
