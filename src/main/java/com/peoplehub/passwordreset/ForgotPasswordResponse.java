package com.peoplehub.passwordreset;

/**
 * Always the identical message, whether or not an email was sent (Spec 13.0, 15.1, D22): the answer
 * never confirms that an organization or an account exists, is active, or was throttled.
 */
public record ForgotPasswordResponse(String message) {}
