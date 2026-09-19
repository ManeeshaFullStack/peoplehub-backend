package com.peoplehub.common.time;

import static org.assertj.core.api.Assertions.assertThat;

import com.peoplehub.support.IntegrationTest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * Proves the injectable-clock pattern (Spec 14.4): a test can replace the application clock with a
 * fixed one, so day-split, DST and org-timezone logic in later phases can be tested
 * deterministically.
 */
@IntegrationTest
@Import(ClockOverrideTest.FixedClockConfig.class)
class ClockOverrideTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-03-08T06:59:59.999Z");

    @Autowired private Clock clock;

    @Test
    void injectedClockIsTheFixedTestClock() {
        assertThat(clock.instant()).isEqualTo(FIXED_INSTANT);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfig {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        }
    }
}
