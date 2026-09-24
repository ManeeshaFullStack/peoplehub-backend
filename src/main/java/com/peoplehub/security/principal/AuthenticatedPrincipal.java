package com.peoplehub.security.principal;

import java.util.Objects;
import java.util.UUID;

/**
 * Who is calling (b2-3, B2-3/14): built from a verified access token <em>and</em> a database check
 * on every request, never from anything else the client sends.
 *
 * <p>{@code organizationId} is the only tenant a service may act in (Spec 2.1.1, 13.0, D21): no
 * request body, path or query value ever overrides it. {@code role} is the employee's role as the
 * database holds it right now, not the role written in the token.
 *
 * @param employeeId the employee ({@code sub})
 * @param organizationId the employee's organization ({@code org})
 * @param role the current role from the database
 * @param sessionId the refresh-token family this access token belongs to ({@code sid})
 */
public record AuthenticatedPrincipal(
        UUID employeeId, UUID organizationId, String role, UUID sessionId) {

    public AuthenticatedPrincipal {
        Objects.requireNonNull(employeeId, "employeeId");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(sessionId, "sessionId");
    }

    /** Whether the caller is a Super Admin (b2-4's minimal role check; the matrix is b3-1). */
    public boolean isSuperAdmin() {
        return "SUPER_ADMIN".equals(role);
    }

    /** Whether the caller is an Admin or a Super Admin. */
    public boolean isAdmin() {
        return "ADMIN".equals(role) || isSuperAdmin();
    }
}
