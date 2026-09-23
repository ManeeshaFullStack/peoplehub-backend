package com.peoplehub.organization;

import jakarta.validation.constraints.NotBlank;

/** {@code POST /public/organizations/verify-email} (b2-2, Spec 2.1.3). */
public record VerifyEmailRequest(@NotBlank String token) {}
