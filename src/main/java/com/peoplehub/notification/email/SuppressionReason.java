package com.peoplehub.notification.email;

/**
 * Why an address is on {@code email_suppression} (b1-4, Spec 9.2, 12). {@code BOUNCE} and {@code
 * COMPLAINT} come from the inbound webhook ({@link EmailWebhookController}); {@code
 * ADDRESS_REJECTED} comes from a synchronous SMTP-time rejection classified by {@link
 * EmailFailureClassifier} inside {@link EmailOutboxProcessor} -- never from the webhook, since that
 * classification is this application's own, not something an external provider reports.
 */
public enum SuppressionReason {
    BOUNCE,
    COMPLAINT,
    ADDRESS_REJECTED
}
