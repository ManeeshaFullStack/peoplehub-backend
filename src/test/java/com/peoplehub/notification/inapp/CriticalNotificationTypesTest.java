package com.peoplehub.notification.inapp;

import static org.assertj.core.api.Assertions.assertThat;

import com.peoplehub.support.IntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Keeps {@link CriticalNotificationTypes#CRITICAL} and V6's {@code
 * ck_notification_preference_critical_always_on} CHECK in step (b1-3), the same "Java and SQL
 * agree" discipline {@code AuditLogMigrationTest} already applies to {@code ActorId}/{@code
 * CorrelationId}. If someone edits the Java constant without a matching migration (or vice versa),
 * this fails.
 */
@IntegrationTest
class CriticalNotificationTypesTest {

    // A representative, deliberately non-critical corpus to probe the database's own opinion.
    private static final List<String> NON_CRITICAL_SAMPLE =
            List.of("LEAVE_APPROVED", "MONTH_LOCKED", "BULK_IMPORT_RESULT", "ORDINARY_TYPE");

    @Autowired private JdbcTemplate jdbc;

    @Test
    void everyJavaCriticalTypeIsRejectedByTheDatabaseWithEitherChannelOff() {
        for (String type : CriticalNotificationTypes.CRITICAL) {
            assertThat(acceptedWithEmailOff(type)).as(type + " email off").isFalse();
            assertThat(acceptedWithInAppOff(type)).as(type + " in_app off").isFalse();
        }
    }

    @Test
    void noNonCriticalSampleTypeIsRejectedByTheDatabase() {
        for (String type : NON_CRITICAL_SAMPLE) {
            assertThat(CriticalNotificationTypes.CRITICAL)
                    .as(type + " is in the sample")
                    .doesNotContain(type);
            assertThat(acceptedWithEmailOff(type)).as(type + " email off").isTrue();
            assertThat(acceptedWithInAppOff(type)).as(type + " in_app off").isTrue();
        }
    }

    private boolean acceptedWithEmailOff(String type) {
        return accepted(type, false, true);
    }

    private boolean acceptedWithInAppOff(String type) {
        return accepted(type, true, false);
    }

    private boolean accepted(String type, boolean email, boolean inApp) {
        try {
            jdbc.update(
                    "INSERT INTO notification_preference (organization_id, employee_id, type, email,"
                            + " in_app) VALUES (?, ?, ?, ?, ?)",
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    type,
                    email,
                    inApp);
            return true;
        } catch (DataIntegrityViolationException e) {
            return false;
        }
    }
}
