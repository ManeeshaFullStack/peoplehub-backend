package com.peoplehub.organization;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /public/organizations/register} (b2-2, Spec 2.1.3). Cross-field and business rules
 * (password confirmation match, timezone validity, password strength, the organization name's
 * availability) are service-level checks, not annotations: Spec 13.2, "custom/service-level rules
 * for cross-field and business checks".
 */
public record RegisterOrganizationRequest(
        @NotBlank @Size(max = 200) String organizationName,
        @NotBlank @Size(max = 64) String timezone,
        @NotBlank @Size(max = 200) String founderFullName,
        @NotBlank @Email @Size(max = 254) String companyEmail,
        @NotBlank String password,
        @NotBlank String confirmPassword,
        @AssertTrue(message = "Terms of service must be accepted.") boolean termsAccepted) {}
