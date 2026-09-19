package com.peoplehub.common.time;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The single source of "now" for the application (Spec 4.2, 14.4).
 *
 * <p>All code must inject {@link Clock} instead of calling {@code Instant.now()} or {@code
 * LocalDate.now()}, so time-based logic (day split, DST-safe durations, org-timezone boundaries)
 * can be tested with a fixed or advancing clock.
 *
 * <p>The clock is UTC because instants carry no zone. The organisation timezone is applied
 * explicitly from org settings when a local date is needed; it is never taken from this clock.
 */
@Configuration(proxyBeanMethods = false)
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
