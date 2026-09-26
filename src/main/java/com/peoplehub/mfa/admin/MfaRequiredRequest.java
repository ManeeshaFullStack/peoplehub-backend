package com.peoplehub.mfa.admin;

import jakarta.validation.constraints.NotNull;

/**
 * {@code PUT /super-admin/employees/{id}/mfa-required} (b2-7, B2-7/1): whether the Super Admin
 * selects this person for {@code REQUIRED_FOR_SELECTED_USERS}.
 */
public record MfaRequiredRequest(@NotNull Boolean required) {}
