package com.peoplehub.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code POST /auth/mfa/enroll} (b2-7, B2-7/9): the {@code ENROLL} challenge token. */
public record MfaEnrollmentChallengeRequest(@NotBlank @Size(max = 128) String challengeToken) {

    /** Never prints the token. */
    @Override
    public String toString() {
        return "MfaEnrollmentChallengeRequest[...]";
    }
}
