package com.peoplehub.security;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The per-account lockout backoff (b2-5, B2-5/P4, R2): how long an account is locked after a given
 * number of consecutive failed sign-ins. Below the threshold there is no lock; at the threshold the
 * lock is the starting length, and every further failure doubles it, up to the cap.
 *
 * <p>With the defaults (threshold 5, 1 minute, 30 minutes): the 5th failure locks for 1 minute, the
 * 6th for 2, then 4, 8, 16, and 30 minutes from the 10th failure on. The values are internal
 * settings, not organization-configurable ({@code [confirm]}, like B2-3/5). Per-IP lockout is not
 * part of b2-5 (B2-5/P5).
 */
@Component
public class AccountLockout {

    private final int threshold;
    private final Duration initial;
    private final Duration max;

    public AccountLockout(
            @Value("${peoplehub.auth.lockout.threshold}") int threshold,
            @Value("${peoplehub.auth.lockout.initial}") Duration initial,
            @Value("${peoplehub.auth.lockout.max}") Duration max) {
        if (threshold < 1
                || initial.isNegative()
                || initial.isZero()
                || max.compareTo(initial) < 0) {
            throw new IllegalStateException(
                    "peoplehub.auth.lockout: threshold must be at least 1, and initial positive"
                            + " and not longer than max");
        }
        this.threshold = threshold;
        this.initial = initial;
        this.max = max;
    }

    /**
     * How long to lock an account that has now failed {@code consecutiveFailures} times in a row;
     * {@link Duration#ZERO} when it should not be locked.
     */
    public Duration lockFor(int consecutiveFailures) {
        if (consecutiveFailures < threshold) {
            return Duration.ZERO;
        }
        Duration lock = initial;
        // Double once per failure past the threshold, stopping at the cap (never overflows).
        for (int i = threshold; i < consecutiveFailures && lock.compareTo(max) < 0; i++) {
            lock = lock.multipliedBy(2);
        }
        return lock.compareTo(max) > 0 ? max : lock;
    }
}
