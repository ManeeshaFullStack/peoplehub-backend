package com.peoplehub.notification.inapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.TestOrganizations;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Application-layer enforcement of "security-/approval-critical types cannot be fully disabled"
 * (Spec 9.3; b1-3) -- the database's {@code ck_notification_preference_critical_always_on} is the
 * backstop ({@code NotificationMigrationTest}, {@code CriticalNotificationTypesTest}), this is the
 * primary control most callers hit.
 *
 * <p>b2-1 (V12) gave {@code notification_preference.organization_id} a real FK, so every test whose
 * {@code service.set(...)} call actually reaches the database needs a real organization row first.
 */
@IntegrationTest
class NotificationPreferenceServiceTest {

    @Autowired private NotificationPreferenceService service;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void defaultsToBothChannelsOnWhenNoRowExistsYet() {
        NotificationPreferenceService.Preference preference =
                service.get(UUID.randomUUID(), UUID.randomUUID(), "ORDINARY_TYPE");

        assertThat(preference.email()).isTrue();
        assertThat(preference.inApp()).isTrue();
    }

    @Test
    void anOrdinaryTypeCanHaveEitherChannelDisabled() {
        UUID org = TestOrganizations.insert(jdbc);
        UUID employee = UUID.randomUUID();

        service.set(org, employee, "ORDINARY_TYPE", false, true);

        NotificationPreferenceService.Preference preference =
                service.get(org, employee, "ORDINARY_TYPE");
        assertThat(preference.email()).isFalse();
        assertThat(preference.inApp()).isTrue();
    }

    @Test
    void settingBothOnForACriticalTypeIsAllowed() {
        UUID org = TestOrganizations.insert(jdbc);
        UUID employee = UUID.randomUUID();

        assertThatCode(() -> service.set(org, employee, "PASSWORD_RESET", true, true))
                .doesNotThrowAnyException();
    }

    @Test
    void disablingEitherChannelForACriticalTypeIsRefused() {
        for (String critical : CriticalNotificationTypes.CRITICAL) {
            UUID org = UUID.randomUUID();
            UUID employee = UUID.randomUUID();

            assertThatThrownBy(() -> service.set(org, employee, critical, false, true))
                    .as(critical + " email off")
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.set(org, employee, critical, true, false))
                    .as(critical + " in_app off")
                    .isInstanceOf(IllegalArgumentException.class);

            // Refused before ever reaching the database: no row was written.
            NotificationPreferenceService.Preference preference =
                    service.get(org, employee, critical);
            assertThat(preference.email()).isTrue();
            assertThat(preference.inApp()).isTrue();
        }
    }

    @Test
    void settingAPreferenceTwiceUpdatesRatherThanDuplicating() {
        UUID org = TestOrganizations.insert(jdbc);
        UUID employee = UUID.randomUUID();

        service.set(org, employee, "ORDINARY_TYPE", false, true);
        service.set(org, employee, "ORDINARY_TYPE", true, false);

        NotificationPreferenceService.Preference preference =
                service.get(org, employee, "ORDINARY_TYPE");
        assertThat(preference.email()).isTrue();
        assertThat(preference.inApp()).isFalse();
    }
}
