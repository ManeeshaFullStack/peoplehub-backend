package com.peoplehub.organization;

/**
 * Deliberately minimal (D29): no employee id, no organization id, no token. Only the organization's
 * own derived login key, which the founder needs for {@code resend-verification} and, later, login.
 */
public record RegisterOrganizationResponse(String organizationLoginKey) {}
