package com.peoplehub.notification.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.peoplehub.notification.inapp.NotificationCreatedEvent;
import com.peoplehub.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link EmailSuppressionNotifier} against real PostgreSQL (b1-4): proves the capability itself
 * works, using synthetic ids -- the same "nothing calls this with real data yet, but it is fully
 * tested" position {@code NotificationWriterTest} already established for the underlying writer.
 */
@IntegrationTest
@RecordApplicationEvents
class EmailSuppressionNotifierTest {

    @Autowired private EmailSuppressionNotifier notifier;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ApplicationEvents events;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
    }

    @Test
    void notifyAdminWritesANotificationAndPublishesItsCreatedEvent() {
        UUID org = UUID.randomUUID();
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
