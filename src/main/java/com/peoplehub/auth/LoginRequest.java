package com.peoplehub.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /auth/login} (b2-3, Spec 2.1.6, 13.0): Organization + company email + password for
 * every role. There is no role field (D27). Only the shape is validated here; whether the
 * organization or account exists is never revealed (B2-3/12), so the email is not format-checked
 * either: a malformed one is simply an account that does not exist.
 *
 * @param organization the organization's login key, or its name (normalized the same way)
 */
public record LoginRequest(
        @NotBlank @Size(max = 200) String organization,
        @NotBlank @Size(max = 254) String email,
        @NotBlank @Size(max = 1024) String password) {

    @Override
    public String toString() {
        // Never print the password (or anything else typed at login).
        return "LoginRequest[...]";
    }
}
