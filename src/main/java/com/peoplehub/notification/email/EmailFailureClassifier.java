package com.peoplehub.notification.email;

import jakarta.mail.SendFailedException;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;
import org.springframework.stereotype.Component;

/**
 * Basic transient-vs-permanent classification of a send failure (b1-2), built only from Jakarta
 * Mail's and Spring Mail's own exception vocabulary -- never a provider-specific rule. Mailpit and
 * Brevo (or any other SMTP server) throw the same Spring {@code MailException} subtypes for the
 * same SMTP-protocol-level reasons, so nothing here branches on which provider is configured.
 */
@Component
public class EmailFailureClassifier {

    /** One classified outcome: whether to retry, and the safe code to store. */
    public record Classification(FailureType type, EmailErrorCode code) {}

    public Classification classify(RuntimeException e) {
        if (e instanceof EmailConfigurationException) {
            return new Classification(FailureType.PERMANENT, EmailErrorCode.CONFIGURATION_ERROR);
        }
        if (e instanceof MailAuthenticationException) {
            // Retrying with the same credentials will not help.
            return new Classification(FailureType.PERMANENT, EmailErrorCode.AUTHENTICATION_FAILED);
        }
        if (e instanceof MailParseException || e instanceof MailPreparationException) {
            // The message itself could not be built (bad address, bad content): a data problem.
            return new Classification(FailureType.PERMANENT, EmailErrorCode.ADDRESS_REJECTED);
        }
        if (e instanceof MailSendException mse) {
            return classifySendFailure(mse);
        }
        // Anything unrecognised gets the benefit of the doubt: transient, so it gets a chance to
        // retry rather than failing permanently on an exception type we have not seen before.
        return new Classification(FailureType.TRANSIENT, EmailErrorCode.SEND_FAILED);
    }

    private Classification classifySendFailure(MailSendException mse) {
        for (Exception failed : mse.getFailedMessages().values()) {
            if (hasInvalidAddress(failed)) {
                return new Classification(FailureType.PERMANENT, EmailErrorCode.ADDRESS_REJECTED);
            }
        }
        // Anything else under MailSendException -- connection refused, timeout, a temporary SMTP
        // reply -- is treated as transient (RFC 5321's own 4xx-vs-5xx distinction, not a provider
        // rule): worth retrying up to RetryPolicy's cap.
        return new Classification(FailureType.TRANSIENT, EmailErrorCode.CONNECTION_FAILED);
    }

    private boolean hasInvalidAddress(Throwable t) {
        for (Throwable cause = t; cause != null; cause = cause.getCause()) {
            if (cause instanceof SendFailedException sfe
                    && sfe.getInvalidAddresses() != null
                    && sfe.getInvalidAddresses().length > 0) {
                return true;
            }
        }
        return false;
    }
}
