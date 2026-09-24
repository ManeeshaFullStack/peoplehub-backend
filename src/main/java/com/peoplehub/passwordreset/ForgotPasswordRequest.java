package com.peoplehub.passwordreset;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /auth/forgot-password} (b2-5, Spec 8.2, 13.0): Organization + company email, the same
 * two fields as login. {@code organization} may be the login key or the organization's name.
 */
public record ForgotPasswordRequest(
        @NotBlank @Size(max = 200) String organization, @NotBlank @Size(max = 254) String email) {}
