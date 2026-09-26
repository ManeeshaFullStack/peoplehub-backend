package com.peoplehub.mfa.admin;

import com.peoplehub.mfa.MfaPolicy;
import jakarta.validation.constraints.NotNull;

/**
 * {@code PUT /organization/security/mfa-policy} (b2-7, B2-7/1, B2-7/3): the new policy, one of
 * {@code DISABLED}, {@code OPTIONAL}, {@code REQUIRED_FOR_ADMINS}, {@code
 * REQUIRED_FOR_SELECTED_USERS} or {@code REQUIRED_FOR_ALL}. There is no organization id: it is
 * always the caller's own.
 */
public record MfaPolicyRequest(@NotNull MfaPolicy policy) {}
