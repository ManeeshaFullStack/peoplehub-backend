package com.peoplehub.notification.email;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The retry/backoff schedule (b1-2, Spec 9.2: "retry with backoff, 3 attempts over minutes"). One
 * ordered list of delays, each applied after the attempt whose number matches its position: the
 * default {@code PT1M,PT5M} means one immediate attempt, a retry after one minute, a retry after
 * five minutes, then {@code FAILED} -- three attempts in total. Configurable ({@code
 * peoplehub.email.retry.delays}, environment {@code PEOPLEHUB_EMAIL_RETRY_DELAYS}); the maximum
 * number of attempts is derived from the list's length so the two can never drift apart.
 *
 * <p>Clock-driven, not {@code Instant.now()}, so the schedule is testable with a fixed or advancing
 * clock (Spec 4.2, CLAUDE.md Section 5).
 */
public class RetryPolicy {

    private final Clock clock;
    private final List<Duration> delays;

    public RetryPolicy(Clock clock, List<Duration> delays) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.delays = List.copyOf(delays);
        if (this.delays.isEmpty()) {
            throw new IllegalArgumentException("At least one retry delay is required");
        }
    }

    /** Total attempts before a row becomes permanently {@code FAILED}: one initial + the delays. */
    public int maxAttempts() {
        return delays.size() + 1;
    }

    /**
     * @param attemptsMade how many attempts have now been made (including the one that just failed)
     * @return whether another attempt is still allowed
     */
    public boolean hasMoreAttempts(int attemptsMade) {
        return attemptsMade < maxAttempts();
    }

    /**
     * When the next attempt is due, computed from now.
     *
     * @param attemptsMade how many attempts have now been made (including the one that just
     *     failed); must satisfy {@link #hasMoreAttempts(int)}
     */
    public Instant nextAttemptAt(int attemptsMade) {
        if (!hasMoreAttempts(attemptsMade)) {
            throw new IllegalArgumentException("No more attempts remain after " + attemptsMade);
        }
        return clock.instant().plus(delays.get(attemptsMade - 1));
    }

    /** Parses a comma-separated list of ISO-8601 durations, for example {@code "PT1M,PT5M"}. */
    public static List<Duration> parseDelays(String commaSeparated) {
        List<Duration> parsed =
                Arrays.stream(commaSeparated.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .map(Duration::parse)
                        .toList();
        if (parsed.isEmpty()) {
            throw new IllegalArgumentException("At least one retry delay is required");
        }
        return parsed;
    }
}
