package com.peoplehub.common.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/**
 * One log line per API call with method, route, status and duration (Spec 14.1); the correlation id
 * and actor id come from MDC. Health checks are excluded so they do not drown the log.
 *
 * <p>The route is the matched <em>template</em> (for example {@code /api/v1/admin/employees/{id}}),
 * never the raw path or the query string: paths can carry ids or email-approval tokens and queries
 * can carry search terms (Spec 15, CLAUDE.md 6). Requests that match no handler log {@value
 * #UNMATCHED}. Nothing from the client (headers, IP, body) is logged.
 *
 * <p>Duration comes from the injected {@link Clock}, so it is testable with a controlled clock.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class RequestLoggingFilter extends OncePerRequestFilter {

    static final String UNMATCHED = "UNMATCHED";
    private static final String CATCH_ALL = "/**";
    private static final String ACTUATOR_PREFIX = "/actuator";

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    private final Clock clock;

    public RequestLoggingFilter(Clock clock) {
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.equals(ACTUATOR_PREFIX) || path.startsWith(ACTUATOR_PREFIX + "/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Instant start = clock.instant();
        // If something escapes every handler the container answers 500; report that, not the
        // default 200 the response still carries at this point, and let the failure propagate.
        int status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
        try {
            chain.doFilter(request, response);
            status = response.getStatus();
        } finally {
            long durationMs = Duration.between(start, clock.instant()).toMillis();
            log.atInfo()
                    .addKeyValue("method", request.getMethod())
                    .addKeyValue("route", route(request))
                    .addKeyValue("status", status)
                    .addKeyValue("durationMs", durationMs)
                    .log("HTTP request completed");
        }
    }

    private static String route(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        // A request no controller claimed lands on the static-resource catch-all "/**": no route.
        return pattern == null || CATCH_ALL.equals(pattern.toString())
                ? UNMATCHED
                : pattern.toString();
    }
}
