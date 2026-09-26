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
 * @param mfa the caller's MFA state under their organization's policy (b2-7, B2-7/20)
 * @param needsAdditionalSuperAdmin true only for a Super Admin whose organization has fewer than
 *     two active Super Admins (b2-7, B2-7/13; Spec 3.3): the onboarding and security screens warn
 *     that nobody inside the organization could recover their account. Informational only; it
 *     blocks nothing, and the count itself is never exposed
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
        Organization organization,
        Mfa mfa,
        boolean needsAdditionalSuperAdmin) {

    /** The employee's own organization: display data only, never another tenant's. */
    public record Organization(String name, String timezone, boolean onboardingCompleted) {}

    /**
     * The caller's MFA state (b2-7, B2-7/20, B2-7/27), all decided on the server.
     *
     * @param enabled whether the caller has confirmed MFA enrollment
     * @param required whether the organization's policy requires MFA of the caller
     * @param policy the organization's MFA policy
     * @param showReminder whether to show the (never blocking) reminder to enable MFA now
     */
    public record Mfa(boolean enabled, boolean required, String policy, boolean showReminder) {}
}
