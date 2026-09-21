package com.peoplehub.common.logging;

import java.util.regex.Pattern;
import org.slf4j.MDC;

/**
 * Actor id conventions (Spec 14.1): every log line carries who caused it, as {@value #MDC_KEY} in
 * MDC. For a request that is the employee id from the token; for scheduled jobs it is {@value
 * #SYSTEM} or the job name. It is an id, never a name, email or phone (Spec 15).
 *
 * <p>Until authentication exists (B2) every request is {@value #ANONYMOUS}. The authentication
 * filter will call {@link #set(String)} once it knows the employee; the entry is always cleared by
 * {@link ActorIdFilter}, which outlives it, so the id never leaks between requests on a pooled
 * thread.
 */
public final class ActorId {

    public static final String MDC_KEY = "actorId";
    public static final String ANONYMOUS = "anonymous";
    public static final String SYSTEM = "SYSTEM";

    // An id ends up in every log line, so it must not be able to carry line breaks or other
    // characters that could forge log lines.
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    private ActorId() {}

    /** Records the actor for the current thread. Rejects anything that is not a plain id. */
    public static void set(String actorId) {
        if (actorId == null || !VALID.matcher(actorId).matches()) {
            throw new IllegalArgumentException("Actor id must match " + VALID.pattern());
        }
        MDC.put(MDC_KEY, actorId);
    }

    /** The actor of the request or job on this thread, or {@code null} outside one. */
    public static String current() {
        return MDC.get(MDC_KEY);
    }

    public static void clear() {
        MDC.remove(MDC_KEY);
    }
}
