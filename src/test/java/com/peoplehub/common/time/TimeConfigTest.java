package com.peoplehub.common.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class TimeConfigTest {

    private final Clock clock = new TimeConfig().clock();

    @Test
    void clockIsUtc() {
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    void clockTracksRealTime() {
        Instant before = Instant.now();
        Instant fromClock = clock.instant();
        Instant after = Instant.now();

        assertThat(fromClock).isBetween(before, after);
        assertThat(Duration.between(before, after)).isLessThan(Duration.ofSeconds(5));
    }
}
