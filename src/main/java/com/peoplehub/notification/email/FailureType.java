package com.peoplehub.notification.email;

/**
 * Whether a send failure is worth retrying (b1-2). Basic classification only, built from Jakarta
 * Mail's/Spring's own exception vocabulary -- never a provider-specific rule (no Brevo-specific
 * logic; see {@link EmailFailureClassifier}).
 */
public enum FailureType {
    /** Might succeed on a later attempt (for example a connection timeout). */
    TRANSIENT,
    /** Will not succeed no matter how many times it is retried. */
    PERMANENT
}
