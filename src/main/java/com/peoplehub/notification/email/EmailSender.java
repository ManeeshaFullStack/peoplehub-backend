package com.peoplehub.notification.email;

/**
 * The provider-agnostic seam {@link EmailOutboxProcessor} sends through (Spec 9.2, b1-2). The only
 * implementation is {@link SmtpEmailSender}, built entirely from Spring's {@code spring.mail.*}
 * properties: plain SMTP, so Mailpit (local) and Brevo's SMTP relay (initial production target) are
 * reached through the exact same code path, distinguished only by configuration. No provider SDK,
 * no provider-specific branch anywhere behind this interface.
 */
public interface EmailSender {

    /**
     * Sends synchronously and returns the {@code Message-ID} this sender generated for the attempt
     * (used as {@code email_outbox.provider_message_id} for correlation; SMTP itself does not hand
     * back a provider-assigned id the way a REST API might).
     *
     * @throws org.springframework.mail.MailException on any send failure (the caller classifies it,
     *     see {@link EmailFailureClassifier})
     * @throws EmailConfigurationException if sending is not configured (for example no
     *     from-address)
     */
    String send(String recipient, String subject, String body);
}
