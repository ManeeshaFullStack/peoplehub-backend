package com.peoplehub.support;

import io.sentry.Hint;
import io.sentry.ITransportFactory;
import io.sentry.Sentry;
import io.sentry.SentryEnvelope;
import io.sentry.SentryOptions;
import io.sentry.transport.ITransport;
import io.sentry.transport.RateLimiter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Replaces only the network transport of Sentry, so a test runs the real SDK, Logback appender and
 * scrubber and reads back the exact JSON envelope that would have left the process. Use with a
 * dummy DSN, for example {@code sentry.dsn=https://public@localhost:1/1}, and call {@link #reset()}
 * when done.
 */
@TestConfiguration(proxyBeanMethods = false)
public class CapturingSentryTransportConfig {

    private static final List<String> ENVELOPES = new CopyOnWriteArrayList<>();

    /** Every event envelope the SDK tried to send, as JSON. */
    public static List<String> events() {
        Sentry.flush(5_000);
        return ENVELOPES.stream().filter(e -> e.contains("\"type\":\"event\"")).toList();
    }

    /** Forgets captured envelopes and shuts the SDK down (it is a process-wide singleton). */
    public static void reset() {
        Sentry.close();
        ENVELOPES.clear();
    }

    @Bean
    ITransportFactory capturingTransportFactory() {
        return (options, requestDetails) -> new CapturingTransport(options);
    }

    private static final class CapturingTransport implements ITransport {

        private final SentryOptions options;

        CapturingTransport(SentryOptions options) {
            this.options = options;
        }

        @Override
        public void send(SentryEnvelope envelope, Hint hint) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try {
                options.getSerializer().serialize(envelope, out);
            } catch (Exception e) {
                throw new IOException(e);
            }
            ENVELOPES.add(out.toString(StandardCharsets.UTF_8));
        }

        @Override
        public void flush(long timeoutMillis) {}

        @Override
        public RateLimiter getRateLimiter() {
            return null;
        }

        @Override
        public void close() {}

        @Override
        public void close(boolean isRestarting) {}
    }
}
