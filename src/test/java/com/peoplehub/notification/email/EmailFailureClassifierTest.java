package com.peoplehub.notification.email;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.mail.SendFailedException;
import jakarta.mail.internet.InternetAddress;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;

/**
 * Transient-vs-permanent classification (b1-2), built only from Jakarta Mail's and Spring Mail's
 * own exception vocabulary -- the same for Mailpit, Brevo or any other SMTP server (no
 * provider-specific rule; see {@link EmailFailureClassifier}).
 */
class EmailFailureClassifierTest {

    private final EmailFailureClassifier classifier = new EmailFailureClassifier();

    @Test
    void configurationProblemsArePermanent() {
        var result = classifier.classify(new EmailConfigurationException("no from-address"));

        assertThat(result.type()).isEqualTo(FailureType.PERMANENT);
        assertThat(result.code()).isEqualTo(EmailErrorCode.CONFIGURATION_ERROR);
    }

    @Test
    void authenticationFailuresArePermanent() {
        var result = classifier.classify(new MailAuthenticationException("bad credentials"));

        assertThat(result.type()).isEqualTo(FailureType.PERMANENT);
        assertThat(result.code()).isEqualTo(EmailErrorCode.AUTHENTICATION_FAILED);
    }

    @Test
    void aMessageThatCouldNotBePreparedIsPermanent() {
        assertThat(classifier.classify(new MailParseException("bad address")).type())
                .isEqualTo(FailureType.PERMANENT);
        assertThat(classifier.classify(new MailPreparationException("bad content")).type())
                .isEqualTo(FailureType.PERMANENT);
    }

    @Test
    void aSendFailureWithAnInvalidAddressIsPermanent() throws Exception {
        SendFailedException sendFailed =
                new SendFailedException(
                        "address rejected",
                        null,
                        new InternetAddress[0],
                        new InternetAddress[0],
                        new InternetAddress[] {new InternetAddress("nobody@example.invalid")});
        MailSendException e = new MailSendException(Map.of("nobody@example.invalid", sendFailed));

        var result = classifier.classify(e);

        assertThat(result.type()).isEqualTo(FailureType.PERMANENT);
        assertThat(result.code()).isEqualTo(EmailErrorCode.ADDRESS_REJECTED);
    }

    @Test
    void aSendFailureWithNoInvalidAddressIsTransientConnectionFailure() throws Exception {
        SendFailedException sendFailed =
                new SendFailedException(
                        "temporary failure",
                        null,
                        new InternetAddress[0],
                        new InternetAddress[] {new InternetAddress("jane@example.com")},
                        new InternetAddress[0]);
        MailSendException e = new MailSendException(Map.of("jane@example.com", sendFailed));

        var result = classifier.classify(e);

        assertThat(result.type()).isEqualTo(FailureType.TRANSIENT);
        assertThat(result.code()).isEqualTo(EmailErrorCode.CONNECTION_FAILED);
    }

    @Test
    void aMailSendExceptionWithNoFailedMessagesIsTransient() {
        // A connection refused/timeout typically surfaces this way: no per-recipient failure map.
        MailSendException e = new MailSendException("could not connect");

        assertThat(classifier.classify(e).type()).isEqualTo(FailureType.TRANSIENT);
    }

    @Test
    void anUnrecognisedFailureDefaultsToTransient() {
        var result = classifier.classify(new IllegalStateException("something unexpected"));

        assertThat(result.type()).isEqualTo(FailureType.TRANSIENT);
        assertThat(result.code()).isEqualTo(EmailErrorCode.SEND_FAILED);
    }
}
