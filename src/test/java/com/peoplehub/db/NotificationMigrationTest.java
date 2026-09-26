package com.peoplehub.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.PrivilegedFixture;
import com.peoplehub.support.SqlErrors;
import com.peoplehub.support.TestOrganizations;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V6 (notification + notification_preference): applies cleanly, both tables have the approved
 * shape, and their constraints reject bad data (b1-3, Spec 9, 9.3, 12). Mirrors {@code
 * EmailOutboxMigrationTest}'s coverage of the equivalent V4 migration. These run as the container's
 * superuser; {@code NotificationRuntimeRoleTest} covers what the runtime role may do.
 */
@IntegrationTest
class NotificationMigrationTest {

    @Autowired @PrivilegedFixture private JdbcTemplate jdbc;

    @Test
    void v6AppliesCleanly() {
        Integer applied =
                jdbc.queryForObject(
                        "SELECT count(*) FROM flyway_schema_history WHERE version = '6' AND success",
                        Integer.class);
        Integer failed =
                jdbc.queryForObject(
                        "SELECT count(*) FROM flyway_schema_history WHERE NOT success",
                        Integer.class);

        assertThat(applied).isEqualTo(1);
        assertThat(failed).isZero();
    }

    // ---- notification ----

    @Test
    void notificationOrganizationAndEmployeeIdsCannotBeNullOrNil() {
        String insert =
                "INSERT INTO notification (organization_id, employee_id, type) VALUES (?, ?, 'X')";

        assertThatThrownBy(() -> jdbc.update(insert, null, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, new UUID(0L, 0L), UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .message()
                .contains("ck_notification_organization_not_nil");
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), new UUID(0L, 0L)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .message()
                .contains("ck_notification_employee_not_nil");
    }

    @Test
    void anOrganizationIdThatDoesNotResolveToARealOrganizationIsRejectedByTheV12ForeignKey() {
        // Before b2-1 (V12), any non-nil UUID satisfied the not-nil CHECK; now organization_id must
        // resolve to a real organization.id row. employee_id is unaffected: no employee-id FK
        // retrofit was part of the b2-1 plan (deliberate, see V12's own comments).
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO notification (organization_id, employee_id,"
                                                + " type) VALUES (?, ?, 'X')",
                                        UUID.randomUUID(),
                                        UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(SqlErrors.sqlState(e)).isEqualTo("23503"));
    }

    @Test
    void notificationTypeMustBeUpperSnakeCase() {
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO notification (organization_id, employee_id, type)"
                                                + " VALUES (?, ?, 'lower_case')",
                                        UUID.randomUUID(),
                                        UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .message()
                .contains("ck_notification_type");
    }

    @Test
    void notificationReadDefaultsToFalseAndCreatedAtIsDatabaseGenerated() {
        UUID org = TestOrganizations.insert(jdbc);
        jdbc.update(
                "INSERT INTO notification (organization_id, employee_id, type) VALUES (?, ?, 'X')",
                org,
                UUID.randomUUID());

        var row =
                jdbc.queryForMap(
                        "SELECT read, created_at <= now() AS timed FROM notification"
                                + " WHERE organization_id = ?",
                        org);

        assertThat(row.get("read")).isEqualTo(false);
        assertThat(row.get("timed")).isEqualTo(true);
    }

    @Test
    void theRowIdIsAlwaysGeneratedNeverSupplied() {
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO notification (id, organization_id, employee_id,"
                                                + " type) VALUES (1, ?, ?, 'X')",
                                        UUID.randomUUID(),
                                        UUID.randomUUID()))
                .satisfies(e -> assertThat(SqlErrors.sqlMessage(e)).contains("GENERATED ALWAYS"));
    }

    @Test
    void bothNotificationIndexesExist() {
        List<String> indexes =
                jdbc.queryForList(
                        "SELECT indexname FROM pg_indexes WHERE tablename = 'notification'",
                        String.class);

        assertThat(indexes)
                .containsExactlyInAnyOrder(
                        "pk_notification",
                        "idx_notification_employee_created",
                        "idx_notification_unread");
    }

    @Test
    void notificationHasExactlyOneForeignKeyAddedByV12AndNoAppendOnlyTrigger() {
        // V6 itself created no FK (neither organization nor employee existed yet); b2-1's V12
        // added fk_notification_organization once organization did. employee_id deliberately stays
        // without a FK: an employee-id retrofit was not part of the b2-1 plan (V12's own comments).
        List<String> foreignKeyNames =
                jdbc.queryForList(
                        "SELECT conname FROM pg_constraint"
                                + " WHERE conrelid = 'notification'::regclass AND contype = 'f'",
                        String.class);
        Integer triggers =
                jdbc.queryForObject(
                        "SELECT count(*) FROM pg_trigger"
                                + " WHERE tgrelid = 'notification'::regclass AND NOT tgisinternal",
                        Integer.class);

        assertThat(foreignKeyNames).containsExactly("fk_notification_organization");
        assertThat(triggers).isZero();
    }

    // ---- notification_preference ----

    @Test
    void preferenceCompositePrimaryKeyRejectsADuplicateRow() {
        UUID org = TestOrganizations.insert(jdbc);
        UUID employee = UUID.randomUUID();
        String insert =
                "INSERT INTO notification_preference (organization_id, employee_id, type)"
                        + " VALUES (?, ?, 'SOME_TYPE')";
        jdbc.update(insert, org, employee);

        assertThatThrownBy(() -> jdbc.update(insert, org, employee))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(SqlErrors.sqlState(e)).isEqualTo("23505"));
    }

    @Test
    void preferenceDefaultsAreBothChannelsOn() {
        UUID org = TestOrganizations.insert(jdbc);
        UUID employee = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO notification_preference (organization_id, employee_id, type)"
                        + " VALUES (?, ?, 'SOME_TYPE')",
                org,
                employee);

        var row =
                jdbc.queryForMap(
                        "SELECT email, in_app FROM notification_preference"
                                + " WHERE organization_id = ? AND employee_id = ?",
                        org,
                        employee);

        assertThat(row.get("email")).isEqualTo(true);
        assertThat(row.get("in_app")).isEqualTo(true);
    }

    @Test
    void criticalTypesCannotBeStoredWithEitherChannelDisabled() {
        for (String critical : List.of("PASSWORD_RESET", "NEW_DEVICE_PAIRED", "EMPLOYEE_INVITED")) {
            String insertEmailOff =
                    "INSERT INTO notification_preference (organization_id, employee_id, type, email,"
                            + " in_app) VALUES (?, ?, ?, false, true)";
            String insertInAppOff =
                    "INSERT INTO notification_preference (organization_id, employee_id, type, email,"
                            + " in_app) VALUES (?, ?, ?, true, false)";

            assertThatThrownBy(
                            () ->
                                    jdbc.update(
                                            insertEmailOff,
                                            UUID.randomUUID(),
                                            UUID.randomUUID(),
                                            critical))
                    .as(critical + " with email off")
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .message()
                    .contains("ck_notification_preference_critical_always_on");
            assertThatThrownBy(
                            () ->
                                    jdbc.update(
                                            insertInAppOff,
                                            UUID.randomUUID(),
                                            UUID.randomUUID(),
                                            critical))
                    .as(critical + " with in_app off")
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .message()
                    .contains("ck_notification_preference_critical_always_on");
        }
    }

    @Test
    void nonCriticalTypesCanHaveEitherChannelDisabled() {
        jdbc.update(
                "INSERT INTO notification_preference (organization_id, employee_id, type, email,"
                        + " in_app) VALUES (?, ?, 'ORDINARY_TYPE', false, true)",
                TestOrganizations.insert(jdbc),
                UUID.randomUUID());
        jdbc.update(
                "INSERT INTO notification_preference (organization_id, employee_id, type, email,"
                        + " in_app) VALUES (?, ?, 'ORDINARY_TYPE', true, false)",
                TestOrganizations.insert(jdbc),
                UUID.randomUUID());
    }
}
