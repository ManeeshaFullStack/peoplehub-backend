package com.peoplehub.common.api.correlation;

import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;

/**
 * Correlation id conventions (Spec 14.1): one id per request, propagated from the caller when it is
 * well formed, generated otherwise, returned in the {@value #HEADER} response header, stored in MDC
 * for logging, and repeated in every error body.
 */
public final class CorrelationId {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    // Restricted charset and length: an inbound id ends up in logs and headers, so it must not be
    // able
    // to carry line breaks or other characters that could forge log lines.
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private CorrelationId() {}

    public static boolean isValid(String candidate) {
        return candidate != null && VALID.matcher(candidate).matches();
    }

    public static String generate() {
        return UUID.randomUUID().toString();
    }

    /** The id of the request being handled on this thread, or {@code null} outside a request. */
    public static String current() {
        return MDC.get(MDC_KEY);
    }
}
