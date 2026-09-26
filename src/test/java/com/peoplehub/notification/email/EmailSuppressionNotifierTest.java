package com.peoplehub.notification.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.peoplehub.common.database.TenantContext;
import com.peoplehub.notification.inapp.NotificationCreatedEvent;
import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.PrivilegedFixture;
import com.peoplehub.support.TestOrganizations;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link EmailSuppressionNotifier} against real PostgreSQL (b1-4): proves the capability itself
 * works, using synthetic ids -- the same "nothing calls this with real data yet, but it is fully
 * tested" position {@code NotificationWriterTest} already established for the underlying writer.
 *
 * <p>b2-1 (V12) gave the underlying {@code notification.organization_id} a real FK, so the one test
 * whose write actually reaches the database needs a real organization row first.
 */
@IntegrationTest
@RecordApplicationEvents
class EmailSuppressionNotifierTest {

    private TenantContext.Scope tenant;

    @AfterEach
    void closeTenant() {
        if (tenant != null) {
            tenant.close();
            tenant = null;
        }
    }

    @Autowired private EmailSuppressionNotifier notifier;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ApplicationEvents events;
    @Autowired @PrivilegedFixture private JdbcTemplate jdbc;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
    }

    @Test
    void notifyAdminWritesANotificationAndPublishesItsCreatedEvent() {
        UUID org = TestOrganizations.insert(jdbc);
        // b2-8 C4 (V25): the tenant an authenticated request or a job would have bound.
        tenant = TenantContext.open(org);
        UUID admin = UUID.randomUUID();

        tx.executeWithoutResult(
                status -> notifier.notifyAdmin(org, admin, SuppressionReason.BOUNCE));

        NotificationCreatedEvent event =
                events.stream(NotificationCreatedEvent.class)
                        .filter(e -> e.organizationId().equals(org))
                        .findFirst()
                        .orElseThrow();
        assertThat(event.employeeId()).isEqualTo(admin);
        assertThat(event.type()).isEqualTo(EmailSuppressionNotifier.NOTIFICATION_TYPE);
    }

    @Test
    void mustRunInATransactionSameAsNotificationWriter() {
        assertThatThrownBy(
                        () ->
                                notifier.notifyAdmin(
                                        UUID.randomUUID(),
                                        UUID.randomUUID(),
                                        SuppressionReason.BOUNCE))
                .isInstanceOf(IllegalTransactionStateException.class);
    }
}
