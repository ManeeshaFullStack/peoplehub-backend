package com.peoplehub.mfa;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /me/mfa/confirm} (b2-7, B2-7/6): the six-digit code the authenticator app shows for
 * the pending secret. Spaces are ignored when it is checked.
 */
public record ConfirmMfaRequest(@NotBlank @Size(max = 16) String code) {

    /** Never prints the code. */
    @Override
    public String toString() {
        return "ConfirmMfaRequest[...]";
    }
}
