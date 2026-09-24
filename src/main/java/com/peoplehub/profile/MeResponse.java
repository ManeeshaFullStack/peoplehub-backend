package com.peoplehub.profile;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The signed-in employee's own profile (b2-3, B2-3/19; b2-4, B2-4/O12): deliberately minimal.
 * {@code department} (Spec 13) arrives with the phase that owns it.
 *
 * @param firstName derived once, server-side, from {@code name} (D17, Spec 10.3): the frontend
 *     never parses names itself
 * @param welcomeSeenAt when the one-time welcome screen was acknowledged, or {@code null} while it
 *     should still be shown (Spec 10.2)
 */
public record MeResponse(
        UUID id,
        String name,
        String firstName,
        String email,
        String role,
        String status,
        LocalDate joinDate,
        Instant welcomeSeenAt,
        Organization organization) {

    /** The employee's own organization: display data only, never another tenant's. */
    public record Organization(String name, String timezone, boolean onboardingCompleted) {}
}
