package com.peoplehub.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /auth/mfa/challenge} (b2-7, B2-7/9, B2-7/11): the challenge token from the login
 * answer and exactly one of a TOTP {@code code} or a {@code recoveryCode}.
 */
public record MfaChallengeRequest(
        @NotBlank @Size(max = 128) String challengeToken,
        @Size(max = 16) String code,
        @Size(max = 64) String recoveryCode) {

    /** Never prints the token or a code. */
    @Override
    public String toString() {
        return "MfaChallengeRequest[...]";
    }
}
