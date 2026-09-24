package com.peoplehub.passwordreset;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /auth/reset-password} (b2-5, B2-5/P6; Spec 8.2, 13.0): the reset code from the email
 * and the new password, twice. The code is in the body, never the path, so it cannot end up in a
 * URL, a request log or a browser history.
 */
public record ResetPasswordRequest(
        @NotBlank @Size(max = 256) String token,
        @NotBlank @Size(max = 1024) String password,
        @NotBlank @Size(max = 1024) String confirmPassword) {

    @Override
    public String toString() {
        return "ResetPasswordRequest[...]";
    }
}
