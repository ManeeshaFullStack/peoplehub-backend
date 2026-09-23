package com.peoplehub.organization;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code POST /public/organizations/resend-verification} (b2-2, Spec 2.1.3, 13.0). */
public record ResendVerificationRequest(
        @NotBlank @Size(max = 200) String organizationLoginKey,
        @NotBlank @Email @Size(max = 254) String companyEmail) {}
