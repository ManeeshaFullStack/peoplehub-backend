package com.peoplehub.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /auth/mfa/enroll/confirm} (b2-7, B2-7/9): the {@code ENROLL} challenge token and the
 * six-digit code the authenticator app shows for the new secret.
 */
public record MfaEnrollmentConfirmRequest(
        @NotBlank @Size(max = 128) String challengeToken, @NotBlank @Size(max = 16) String code) {

    /** Never prints the token or the code. */
    @Override
    public String toString() {
        return "MfaEnrollmentConfirmRequest[...]";
    }
}
