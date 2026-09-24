package com.peoplehub.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The lockout backoff (b2-5, B2-5/P4, R2): 1 minute at the 5th failure, doubling to 30. */
class AccountLockoutTest {

    private final AccountLockout lockout =
            new AccountLockout(5, Duration.ofMinutes(1), Duration.ofMinutes(30));

    @Test
    void belowTheThresholdThereIsNoLock() {
        for (int failures = 0; failures < 5; failures++) {
            assertThat(lockout.lockFor(failures)).as("%d failures", failures).isZero();
        }
    }

    @Test
    void theLockStartsAtOneMinuteAndDoublesUpToTheCap() {
        assertThat(lockout.lockFor(5)).isEqualTo(Duration.ofMinutes(1));
        assertThat(lockout.lockFor(6)).isEqualTo(Duration.ofMinutes(2));
        assertThat(lockout.lockFor(7)).isEqualTo(Duration.ofMinutes(4));
        assertThat(lockout.lockFor(8)).isEqualTo(Duration.ofMinutes(8));
        assertThat(lockout.lockFor(9)).isEqualTo(Duration.ofMinutes(16));
        assertThat(lockout.lockFor(10)).isEqualTo(Duration.ofMinutes(30));
        assertThat(lockout.lockFor(11)).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void aHugeFailureCountStaysAtTheCapWithoutOverflowing() {
        assertThat(lockout.lockFor(Integer.MAX_VALUE)).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void aCapThatIsNotAPowerOfTwoMultipleIsStillRespected() {
        AccountLockout odd = new AccountLockout(1, Duration.ofSeconds(45), Duration.ofSeconds(100));

        assertThat(odd.lockFor(1)).isEqualTo(Duration.ofSeconds(45));
        assertThat(odd.lockFor(2)).isEqualTo(Duration.ofSeconds(90));
        assertThat(odd.lockFor(3)).isEqualTo(Duration.ofSeconds(100));
    }

    @Test
    void anInvalidConfigurationRefusesToStart() {
        assertThatThrownBy(
                        () -> new AccountLockout(0, Duration.ofMinutes(1), Duration.ofMinutes(30)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AccountLockout(5, Duration.ZERO, Duration.ofMinutes(30)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(
                        () -> new AccountLockout(5, Duration.ofMinutes(-1), Duration.ofMinutes(30)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(
                        () -> new AccountLockout(5, Duration.ofMinutes(10), Duration.ofMinutes(5)))
                .isInstanceOf(IllegalStateException.class);
    }
}
