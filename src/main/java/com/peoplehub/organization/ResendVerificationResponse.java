package com.peoplehub.organization;

/**
 * Always the identical message whether or not anything matched (Spec 13.0, D22): resend is
 * non-enumerating, so it never confirms or denies that a given organization/email combination has a
 * pending registration.
 */
public record ResendVerificationResponse(String message) {}
