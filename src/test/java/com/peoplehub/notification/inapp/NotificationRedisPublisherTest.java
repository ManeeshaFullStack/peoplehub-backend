package com.peoplehub.notification.inapp;

import static org.assertj.core.api.Assertions.assertThat;

import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.TestOrganizations;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link NotificationRedisPublisher} publishes only after the writing transaction actually commits,
 * never for a rolled-back write (b1-3). Subscribes directly to the raw Redis channel (bypassing
 * {@link NotificationBroadcastService}/SSE) so this test proves the publish step in isolation.
 *
 * <p>b2-1 (V12) gave {@code notification.organization_id} a real FK: both tests' own {@code
 * writer.append} call must still succeed as an INSERT (even the one that then rolls back), so both
 * need a real organization row first.
 */
@IntegrationTest
class NotificationRedisPublisherTest {

    @Autowired private NotificationWriter writer;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private RedisMessageListenerContainer container;
    @Autowired private JdbcTemplate jdbc;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
    }

    @Test
    void aCommittedWriteIsPublishedToItsChannel() throws Exception {
        UUID org = TestOrganizations.insert(jdbc);
        UUID employee = UUID.randomUUID();
        LinkedBlockingQueue<String> received = new LinkedBlockingQueue<>();
        ChannelTopic topic = new ChannelTopic(NotificationChannel.of(org, employee));
        MessageListener listener =
                (message, pattern) -> received.add(new String(message.getBody()));
        container.addMessageListener(listener, topic);
        try {
            tx.executeWithoutResult(
                    status ->
                            writer.append(
                                    NotificationMessage.builder(org, employee, "SOMETHING_HAPPENED")
                                            .build()));

            String payload = received.poll(5, TimeUnit.SECONDS);
            assertThat(payload).as("a message was published").isNotNull();
            assertThat(payload).contains("SOMETHING_HAPPENED");
        } finally {
            container.removeMessageListener(listener, topic);
        }
    }

    @Test
    void aRolledBackWriteIsNeverPublished() throws Exception {
        UUID org = TestOrganizations.insert(jdbc);
        UUID employee = UUID.randomUUID();
        LinkedBlockingQueue<String> received = new LinkedBlockingQueue<>();
        ChannelTopic topic = new ChannelTopic(NotificationChannel.of(org, employee));
        MessageListener listener =
                (message, pattern) -> received.add(new String(message.getBody()));
        container.addMessageListener(listener, topic);
        try {
            try {
                tx.executeWithoutResult(
                        status -> {
                            writer.append(
                                    NotificationMessage.builder(org, employee, "SOMETHING_HAPPENED")
                                            .build());
                            status.setRollbackOnly();
                        });
            } catch (RuntimeException ignored) {
                // setRollbackOnly alone does not throw; this catch is defensive only.
            }

            String payload = received.poll(2, TimeUnit.SECONDS);
            assertThat(payload).as("nothing was published for a rolled-back write").isNull();
        } finally {
            container.removeMessageListener(listener, topic);
        }
    }
}
