package com.peoplehub.notification.email;

/**
 * Sending is not configured (b1-2) -- for example {@code peoplehub.email.from-address} ({@code
 * PEOPLEHUB_EMAIL_FROM_ADDRESS}) is unset. Thrown by {@link SmtpEmailSender} before any network
 * call, and treated by {@link EmailOutboxProcessor} as a permanent failure ({@link
 * EmailErrorCode#CONFIGURATION_ERROR}): retrying without an operator fixing the configuration will
 * never succeed.
 */
public class EmailConfigurationException extends RuntimeException {

    public EmailConfigurationException(String message) {
        super(message);
    }
}
