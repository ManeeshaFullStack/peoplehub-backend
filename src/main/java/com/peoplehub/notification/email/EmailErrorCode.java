package com.peoplehub.notification.email;

/**
 * A safe, closed set of reasons an outbox row failed (b1-2). Stored in {@code email_outbox.error}.
 * Never the raw exception text: an SMTP/provider exception message can carry personal data (a
 * recipient address, a diagnostic detail), so only these fixed codes are ever written, the same
 * message-free discipline the logging layer already applies (D5/D6).
 */
public enum EmailErrorCode {
    /** The SMTP server rejected the address itself (a permanent, non-retryable failure). */
    ADDRESS_REJECTED,
    /** SMTP authentication failed; retrying with the same credentials will not help. */
    AUTHENTICATION_FAILED,
    /** The template for this {@code type} does not exist, or a required token was missing. */
    TEMPLATE_ERROR,
    /**
     * Sending is not configured (for example no from-address); an operator problem, not a retry.
     */
    CONFIGURATION_ERROR,
    /** Could not reach the SMTP server (connection/timeout); typically transient. */
    CONNECTION_FAILED,
    /** Any other send failure not classified above. */
    SEND_FAILED,
    /** The recipient is on the suppression list (b1-4): never attempted, not a real failure. */
    SUPPRESSED
}
