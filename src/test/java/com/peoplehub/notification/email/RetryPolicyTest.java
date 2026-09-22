package com.peoplehub.notification.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The retry/backoff schedule (b1-2, Spec 9.2: "3 attempts over minutes"). A fixed clock stands in
 * for the passage of time, matching {@code CatchUpPatternTest}'s style for other clock-driven
 * logic.
 */
class RetryPolicyTest {

    private static final Instant NOW = Instant.parse("2026-03-10T10:00:00Z");

    private static RetryPolicy policy(Duration... delays) {
        return new RetryPolicy(Clock.fixed(NOW, ZoneOffset.UTC), List.of(delays));
    }

    @Test
    void threeAttemptsMeansOneInitialPlusTwoDelays() {
        RetryPolicy policy = policy(Duration.ofMinutes(1), Duration.ofMinutes(5));

        assertThat(policy.maxAttempts()).isEqualTo(3);
    }

    @Test
    void hasMoreAttemptsUntilTheScheduleIsExhausted() {
        RetryPolicy policy = policy(Duration.ofMinutes(1), Duration.ofMinutes(5));

        assertThat(policy.hasMoreAttempts(1)).isTrue(); // after the 1st (initial) attempt failed
        assertThat(policy.hasMoreAttempts(2)).isTrue(); // after the 1st retry failed
        assertThat(policy.hasMoreAttempts(3)).isFalse(); // after the 2nd retry failed: exhausted
    }

    @Test
    void nextAttemptAtUsesTheDelayMatchingHowManyAttemptsHaveBeenMade() {
        RetryPolicy policy = policy(Duration.ofMinutes(1), Duration.ofMinutes(5));

        assertThat(policy.nextAttemptAt(1)).isEqualTo(NOW.plus(Duration.ofMinutes(1)));
        assertThat(policy.nextAttemptAt(2)).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
    }

    @Test
    void nextAttemptAtRefusesOnceExhausted() {
        RetryPolicy policy = policy(Duration.ofMinutes(1), Duration.ofMinutes(5));

        assertThatThrownBy(() -> policy.nextAttemptAt(3))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aSingleDelayMeansTwoAttemptsInTotal() {
        RetryPolicy policy = policy(Duration.ofMinutes(1));

        assertThat(policy.maxAttempts()).isEqualTo(2);
        assertThat(policy.hasMoreAttempts(1)).isTrue();
        assertThat(policy.hasMoreAttempts(2)).isFalse();
    }

    @Test
    void atLeastOneDelayIsRequired() {
        assertThatThrownBy(() -> new RetryPolicy(Clock.fixed(NOW, ZoneOffset.UTC), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseDelaysReadsTheConfiguredCommaSeparatedSchedule() {
        assertThat(RetryPolicy.parseDelays("PT1M,PT5M"))
                .containsExactly(Duration.ofMinutes(1), Duration.ofMinutes(5));
        assertThat(RetryPolicy.parseDelays(" PT1M , PT5M "))
                .as("whitespace around entries is tolerated")
                .containsExactly(Duration.ofMinutes(1), Duration.ofMinutes(5));
    }

    @Test
    void parseDelaysRejectsAnEmptySchedule() {
        assertThatThrownBy(() -> RetryPolicy.parseDelays(""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
