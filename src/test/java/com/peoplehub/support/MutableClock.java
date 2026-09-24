package com.peoplehub.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * A clock a test can move forward (b2-3: token and session expiry). Starts at the real current time
 * so database rows written with {@code now()} stay consistent with it. Import {@link Config} to
 * make it the application clock; that gives the test class its own application context.
 */
public final class MutableClock extends Clock {

    private final AtomicReference<Instant> now;

    public MutableClock(Instant start) {
        this.now = new AtomicReference<>(start);
    }

    public void advance(Duration duration) {
        now.updateAndGet(current -> current.plus(duration));
    }

    public void set(Instant instant) {
        now.set(instant);
    }

    @Override
    public Instant instant() {
        return now.get();
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return Clock.fixed(instant(), zone);
    }

    /** Replaces the application clock with a {@link MutableClock}. */
    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {

        @Bean
        @Primary
        MutableClock mutableClock() {
            // Microseconds, the precision PostgreSQL stores, so an instant written from this clock
            // reads back equal.
            return new MutableClock(Instant.now().truncatedTo(ChronoUnit.MICROS));
        }
    }
}
