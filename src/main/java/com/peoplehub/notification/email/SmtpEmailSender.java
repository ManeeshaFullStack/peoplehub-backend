package com.peoplehub.notification.email;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.util.UUID;
import org.springframework.mail.MailParseException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

/**
 * The only {@link EmailSender} implementation (b1-2): plain SMTP via Spring's auto-configured
 * {@link JavaMailSender}, built from {@code spring.mail.*} properties (host, port, username,
 * password, STARTTLS). Mailpit locally and Brevo's SMTP relay in production are just different
 * property values -- this class has exactly one code path and never inspects which provider it is
 * talking to.
 *
 * <p>The sender address is required and has no hardcoded default ({@code
 * peoplehub.email.from-address}, environment variable {@code PEOPLEHUB_EMAIL_FROM_ADDRESS}). It is
 * checked here, at send time, not at application startup: a missing value fails every send attempt
 * loudly (a permanent, non-retryable {@link EmailConfigurationException}) rather than refusing the
 * whole application to start, which would break every test and every deployment that does not yet
 * send email.
 *
 * <p>SMTP has no provider-assigned message id the way a REST API might, so the returned id is a
 * UUID this class generates purely for {@code email_outbox.provider_message_id} correlation, not a
 * value any provider gave back.
 */
public class SmtpEmailSender implements EmailSender {

    private final JavaMailSender mailSender;
    private final String fromAddress;

    public SmtpEmailSender(JavaMailSender mailSender, String fromAddress) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
    }

    @Override
    public String send(String recipient, String subject, String body) {
        if (fromAddress == null || fromAddress.isBlank()) {
            throw new EmailConfigurationException(
                    "peoplehub.email.from-address (PEOPLEHUB_EMAIL_FROM_ADDRESS) is not set");
        }
        MimeMessage message = mailSender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(recipient);
            helper.setSubject(subject);
            helper.setText(body, false);
        } catch (MessagingException e) {
            // A malformed address/subject/body is a data problem, not a transient one: wrap as
            // Spring's own unchecked "could not be prepared" type so EmailFailureClassifier treats
            // it as permanent, the same as it would treat any other MailParseException.
            throw new MailParseException("Email message could not be prepared", e);
        }
        mailSender.send(message);
        return UUID.randomUUID().toString();
    }
}
