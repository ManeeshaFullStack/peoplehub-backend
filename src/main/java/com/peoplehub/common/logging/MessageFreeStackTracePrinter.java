package com.peoplehub.common.logging;

import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.springframework.boot.logging.StackTracePrinter;

/**
 * Renders a throwable as class names and frames only, never its message (Spec 14.1, 15).
 *
 * <p>Exception messages routinely carry personal data: a Postgres unique violation quotes the
 * offending key value, a parse error quotes the input. Rendering the message would put that in the
 * log aggregator, so this printer is configured as the JSON stack-trace renderer (see {@code
 * logging.structured.json.stacktrace.printer}) and the message is dropped at the source. What
 * remains is what is needed to investigate: what was thrown, where, and the cause chain, tied to
 * the request by the correlation id.
 *
 * <p>Loaded by Spring Boot from the property, so it must stay public with a no-argument
 * constructor.
 */
public class MessageFreeStackTracePrinter implements StackTracePrinter {

    /** Caps runaway cause chains; a real chain is a handful deep. */
    private static final int MAX_CAUSES = 20;

    @Override
    public void printStackTrace(Throwable throwable, Appendable out) throws IOException {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = throwable;
        int depth = 0;
        while (current != null && seen.add(current) && depth < MAX_CAUSES) {
            out.append(depth == 0 ? "" : "Caused by: ").append(current.getClass().getName());
            for (StackTraceElement frame : current.getStackTrace()) {
                out.append("\n\tat ").append(frame.toString());
            }
            for (Throwable suppressed : current.getSuppressed()) {
                out.append("\n\tSuppressed: ").append(suppressed.getClass().getName());
            }
            current = current.getCause();
            depth++;
            if (current != null) {
                out.append('\n');
            }
        }
    }
}
