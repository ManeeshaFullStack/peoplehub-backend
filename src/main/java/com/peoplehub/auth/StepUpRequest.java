package com.peoplehub.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /me/step-up} (b2-7, B2-7/15): the caller's password and, when they have MFA enabled,
 * exactly one of a TOTP {@code code} or a {@code recoveryCode}. There is no employee id: the
 * account and the session are always the caller's.
 */
public record StepUpRequest(
        @NotBlank @Size(max = 1024) String password,
        @Size(max = 16) String code,
        @Size(max = 64) String recoveryCode) {

    /** Never prints the password or a code. */
    @Override
    public String toString() {
        return "StepUpRequest[...]";
    }
}
