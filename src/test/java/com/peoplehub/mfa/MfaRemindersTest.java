package com.peoplehub.mfa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** When the server shows the MFA reminder (b2-7, B2-7/27; MFA/7). */
class MfaRemindersTest {

    private static final Instant NOW = Instant.parse("2026-09-26T10:00:00Z");
    private static final Duration INTERVAL = Duration.ofDays(7);

    private final MfaReminders reminders =
            new MfaReminders(null, Clock.fixed(NOW, ZoneOffset.UTC), INTERVAL);

    @ParameterizedTest
    @EnumSource(value = MfaPolicy.class, names = "DISABLED", mode = EnumSource.Mode.EXCLUDE)
    void anEligiblePersonWhoNeverDismissedItIsReminded(MfaPolicy policy) {
        assertThat(reminders.show(policy, false, false, null)).isTrue();
    }

    @Test
    void nobodyIsRemindedWhileMfaIsDisabled() {
        assertThat(reminders.show(MfaPolicy.DISABLED, false, false, null)).isFalse();
    }

    @Test
    void aRequiredPersonIsNeverReminded() {
        assertThat(reminders.show(MfaPolicy.REQUIRED_FOR_ALL, true, false, null)).isFalse();
    }

    @Test
    void anEnrolledPersonIsNeverReminded() {
        assertThat(reminders.show(MfaPolicy.OPTIONAL, false, true, null)).isFalse();
    }

    @Test
    void aDismissalHidesItForOneInterval() {
        assertThat(reminders.show(MfaPolicy.OPTIONAL, false, false, NOW)).isFalse();
        assertThat(
                        reminders.show(
                                MfaPolicy.OPTIONAL,
                                false,
                                false,
                                NOW.minus(INTERVAL).plusSeconds(1)))
                .isFalse();
        assertThat(reminders.show(MfaPolicy.OPTIONAL, false, false, NOW.minus(INTERVAL))).isTrue();
        assertThat(reminders.show(MfaPolicy.OPTIONAL, false, false, NOW.minus(Duration.ofDays(30))))
                .isTrue();
    }

    @Test
    void theIntervalMustBePositive() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        assertThatThrownBy(() -> new MfaReminders(null, clock, Duration.ZERO))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("peoplehub.mfa.reminder-interval");
        assertThatThrownBy(() -> new MfaReminders(null, clock, Duration.ofDays(-1)))
                .isInstanceOf(IllegalStateException.class);
    }
}
