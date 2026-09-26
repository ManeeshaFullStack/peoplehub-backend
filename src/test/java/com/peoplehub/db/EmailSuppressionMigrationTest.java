package com.peoplehub.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.peoplehub.notification.email.SuppressionReason;
import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.PrivilegedFixture;
import com.peoplehub.support.SqlErrors;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V7 (email suppression): applies cleanly, the table has the approved shape, and its constraints
 * reject bad data (b1-4, Spec 9.2, 12). Mirrors {@code NotificationMigrationTest}'s coverage of the
 * equivalent V6 migration. Runs as the container's superuser; {@code
 * EmailSuppressionRuntimeRoleTest} covers what the runtime role may do.
 */
@IntegrationTest
class EmailSuppressionMigrationTest {

    @Autowired @PrivilegedFixture private JdbcTemplate jdbc;

    // @IntegrationTest does not roll back between methods, and this table's primary key is the
    // email address itself, so a row left over from one test collides with the next (the same
    // pattern EmailOutboxProcessorTest already guards against).
    @BeforeEach
    void emptyTheSuppressionList() {
        jdbc.update("DELETE FROM email_suppression");
    }

    @Test
    void v7AppliesCleanly() {
        Integer applied =
                jdbc.queryForObject(
                        "SELECT count(*) FROM flyway_schema_history WHERE version = '7' AND success",
                        Integer.class);
        Integer failed =
                jdbc.queryForObject(
                        "SELECT count(*) FROM flyway_schema_history WHERE NOT success",
                        Integer.class);

        assertThat(applied).isEqualTo(1);
        assertThat(failed).isZero();
    }

    @Test
    void thereIsNoOrganizationIdColumnAtAll() {
        List<String> columns =
                jdbc.queryForList(
                        "SELECT column_name FROM information_schema.columns"
                                + " WHERE table_name = 'email_suppression'",
                        String.class);

        assertThat(columns).containsExactlyInAnyOrder("email", "reason", "since");
    }

    @Test
    void emailIsThePrimaryKeyAndCannotBeBlank() {
        jdbc.update(
                "INSERT INTO email_suppression (email, reason) VALUES ('jane@example.com', 'BOUNCE')");

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO email_suppression (email, reason)"
                                                + " VALUES ('jane@example.com', 'COMPLAINT')"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(SqlErrors.sqlState(e)).isEqualTo("23505"));

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO email_suppression (email, reason) VALUES ('', 'BOUNCE')"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .message()
                .contains("ck_email_suppression_email_not_blank");
    }

    @Test
    void reasonAcceptsExactlyEveryJavaSuppressionReasonValue() {
        // Kept in step with SuppressionReason by driving the DB check from the enum itself, rather
        // than a hardcoded parallel list -- the same drift-protection CriticalNotificationTypesTest
        // already applies to the notification preference table's own CHECK constraint.
        for (SuppressionReason reason : SuppressionReason.values()) {
            jdbc.update(
                    "INSERT INTO email_suppression (email, reason) VALUES (?, ?)",
                    reason.name().toLowerCase() + "@example.com",
                    reason.name());
        }

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO email_suppression (email, reason)"
                                                + " VALUES ('other@example.com', 'BOGUS')"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .message()
                .contains("ck_email_suppression_reason");
    }

    @Test
    void sinceDefaultsToTheDatabaseClock() {
        jdbc.update(
                "INSERT INTO email_suppression (email, reason) VALUES ('jane@example.com', 'BOUNCE')");

        Boolean fromDatabaseClock =
                jdbc.queryForObject(
                        "SELECT since <= now() AND since > now() - interval '5 minutes'"
                                + " FROM email_suppression WHERE email = 'jane@example.com'",
                        Boolean.class);

        assertThat(fromDatabaseClock).isTrue();
    }

    @Test
    void thereIsNoSecondaryIndexBeyondThePrimaryKey() {
        List<String> indexes =
                jdbc.queryForList(
                        "SELECT indexname FROM pg_indexes WHERE tablename = 'email_suppression'",
                        String.class);

        assertThat(indexes).containsExactly("pk_email_suppression");
    }
}
