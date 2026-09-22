package com.peoplehub.notification.email;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * {@code peoplehub.email.from-address} is required with no default and no fake placeholder sender
 * (b1-2 review adjustment): a {@code null} or blank value must fail loudly at send time, never send
 * from a made-up address. The guard fires before any network call, so a real {@code JavaMailSender}
 * is not needed to prove it -- both cases below never touch the {@code null} one this test passes.
 */
class SmtpEmailSenderTest {

    @Test
    void aNullFromAddressFailsWithNoNetworkCall() {
        SmtpEmailSender sender = new SmtpEmailSender(null, null);

        assertThatThrownBy(() -> sender.send("jane@example.com", "Subject", "Body"))
                .isInstanceOf(EmailConfigurationException.class)
                .hasMessageContaining("PEOPLEHUB_EMAIL_FROM_ADDRESS");
    }

    @Test
    void aBlankFromAddressFailsTheSameWay() {
        SmtpEmailSender sender = new SmtpEmailSender(null, "   ");

        assertThatThrownBy(() -> sender.send("jane@example.com", "Subject", "Body"))
                .isInstanceOf(EmailConfigurationException.class);
    }
}
