package com.peoplehub.mfa;

/**
 * An organization's MFA policy (b2-7, B2-7/1; MFA/3, MFA/4; Spec D6, 8.3), stored in {@code
 * organization.mfa_policy} (V19). A new organization starts {@link #DISABLED}.
 *
 * <p>A policy <em>covers</em> a person when it requires them to have MFA. Under a {@code
 * REQUIRED_*} policy, the people it does not cover are treated as under {@link #OPTIONAL}. Whoever
 * has enrolled is challenged at sign-in whatever the policy (B2-7/2); that is not decided here.
 */
public enum MfaPolicy {
    /** MFA is off: nobody is required, enrollment is not offered, no reminders. */
    DISABLED,
    /** Nobody is required; anyone may enroll voluntarily. */
    OPTIONAL,
    /** Admins and Super Admins are required; Employees may enroll voluntarily. */
    REQUIRED_FOR_ADMINS,
    /** The people a Super Admin selected ({@code employee.mfa_required}) are required. */
    REQUIRED_FOR_SELECTED_USERS,
    /** Everyone is required. */
    REQUIRED_FOR_ALL;

    /** Whether people may enroll at all under this policy. */
    public boolean offersEnrollment() {
        return this != DISABLED;
    }

    /**
     * Whether this policy requires MFA of a person.
     *
     * @param role the person's current role ({@code EMPLOYEE}, {@code ADMIN} or {@code
     *     SUPER_ADMIN})
     * @param selected the Super Admin's selection ({@code employee.mfa_required}); it may be set
     *     under any policy but counts only under {@link #REQUIRED_FOR_SELECTED_USERS}
     */
    public boolean covers(String role, boolean selected) {
        return switch (this) {
            case DISABLED, OPTIONAL -> false;
            case REQUIRED_FOR_ADMINS -> "ADMIN".equals(role) || "SUPER_ADMIN".equals(role);
            case REQUIRED_FOR_SELECTED_USERS -> selected;
            case REQUIRED_FOR_ALL -> true;
        };
    }
}
