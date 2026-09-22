package com.peoplehub.notification.email;

import static org.assertj.core.api.Assertions.assertThat;

import com.peoplehub.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** {@link EmailSuppressionService} against real PostgreSQL (b1-4). */
@IntegrationTest
class EmailSuppressionServiceTest {

    @Autowired private EmailSuppressionService service;
    @Autowired private JdbcTemplate jdbc;

    private static String uniqueEmail() {
        return "test-" + UUID.randomUUID() + "@example.com";
    }

    @Test
    void anAddressIsNotSuppressedUntilItIs() {
        String email = uniqueEmail();

        assertThat(service.isSuppressed(email)).isFalse();

        service.suppress(email, SuppressionReason.BOUNCE);

        assertThat(service.isSuppressed(email)).isTrue();
    }

    @Test
    void suppressingTwiceIsIdempotentAndKeepsTheFirstReason() {
        String email = uniqueEmail();

        service.suppress(email, SuppressionReason.BOUNCE);
        service.suppress(email, SuppressionReason.COMPLAINT);

        String reason =
                jdbc.queryForObject(
                        "SELECT reason FROM email_suppression WHERE email = ?",
                        String.class,
                        email);
        assertThat(reason).isEqualTo("BOUNCE");
    }

    @Test
    void differentAddressesAreIndependent() {
        String suppressed = uniqueEmail();
        String other = uniqueEmail();

        service.suppress(suppressed, SuppressionReason.ADDRESS_REJECTED);

        assertThat(service.isSuppressed(suppressed)).isTrue();
        assertThat(service.isSuppressed(other)).isFalse();
    }
}
