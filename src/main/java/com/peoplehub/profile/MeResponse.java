package com.peoplehub.profile;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The signed-in employee's own profile (b2-3, B2-3/19): deliberately minimal. {@code firstName},
 * {@code welcomeSeenAt} and {@code department} (Spec 13) arrive with the phases that own them.
 */
public record MeResponse(
        UUID id,
        String name,
        String email,
        String role,
        String status,
        LocalDate joinDate,
        Organization organization) {

    /** The employee's own organization: display data only, never another tenant's. */
    public record Organization(String name, String timezone, boolean onboardingCompleted) {}
}
