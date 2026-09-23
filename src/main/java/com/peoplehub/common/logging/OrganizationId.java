package com.peoplehub.common.logging;

import java.util.Objects;
import java.util.UUID;
import org.slf4j.MDC;

/**
 * The caller's organization in MDC, next to the actor id (b2-3, B2-3/18; Spec 15.1: an internal
 * organization id may be logged for support correlation, never exposed across tenants). Set once a
 * request is authenticated; {@link ActorIdFilter} clears it with the actor id, so it never outlives
 * the request. Absent for unauthenticated requests and for jobs.
 */
public final class OrganizationId {

    public static final String MDC_KEY = "organizationId";

    private OrganizationId() {}

    public static void set(UUID organizationId) {
        MDC.put(MDC_KEY, Objects.requireNonNull(organizationId, "organizationId").toString());
    }

    /** The organization of the request on this thread, or {@code null}. */
    public static String current() {
        return MDC.get(MDC_KEY);
    }

    public static void clear() {
        MDC.remove(MDC_KEY);
    }
}
