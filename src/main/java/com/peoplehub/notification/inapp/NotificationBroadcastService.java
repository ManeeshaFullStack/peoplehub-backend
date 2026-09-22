package com.peoplehub.notification.inapp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Subscribes an {@link SseEmitter} to one employee's Redis channel (b1-3, decision: Redis is the
 * cross-instance notification channel -- not an in-memory {@code SseEmitter} registry alone). A
 * message published by {@link NotificationRedisPublisher} on <em>any</em> application instance
 * reaches every emitter subscribed to that channel on <em>every</em> instance, which is exactly
 * what makes this cross-instance: a plain in-JVM emitter map would only ever reach clients
 * connected to the one instance that happened to write the notification.
 *
 * <p>Uses the {@link RedisMessageListenerContainer} Spring Boot itself auto-configures (this Spring
 * Boot version registers one on its own): defining a second one here caused an ambiguous-bean
 * startup failure across the whole test suite, caught by running the full verification before
 * committing (the new CI-conscious discipline) rather than by a narrow unit test.
 */
@Component
public class NotificationBroadcastService {

    private final RedisMessageListenerContainer container;

    public NotificationBroadcastService(RedisMessageListenerContainer container) {
        this.container = container;
    }

    /** A new SSE connection for one employee. No timeout: the client reconnects if it drops. */
    public SseEmitter subscribe(UUID organizationId, UUID employeeId) {
        SseEmitter emitter = new SseEmitter(0L);
        ChannelTopic topic = new ChannelTopic(NotificationChannel.of(organizationId, employeeId));
        MessageListener listener =
                (message, pattern) -> {
                    try {
                        emitter.send(
                                SseEmitter.event()
                                        .name("notification")
                                        .data(
                                                new String(
                                                        message.getBody(),
                                                        StandardCharsets.UTF_8)));
                    } catch (IOException | IllegalStateException e) {
                        // The client is gone; let onCompletion/onError below do the unsubscribe.
                        emitter.completeWithError(e);
                    }
                };
        container.addMessageListener(listener, topic);

        Runnable unsubscribe = () -> container.removeMessageListener(listener, topic);
        emitter.onCompletion(unsubscribe);
        emitter.onTimeout(unsubscribe);
        emitter.onError(throwable -> unsubscribe.run());
        return emitter;
    }
}
