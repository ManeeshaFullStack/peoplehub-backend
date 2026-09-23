package com.peoplehub.security;

import java.util.List;
import org.springframework.http.server.PathContainer;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * The only requests that need no authentication (b2-3, B2-3 implementation plan). Everything else
 * is denied until authenticated: a new endpoint is private unless it is added here on purpose.
 *
 * <p>Paths are full request paths, including the {@code /api/v1} prefix that {@code WebConfig} adds
 * to controllers.
 */
public final class PublicEndpoints {

    static final List<String> PATTERNS =
            List.of(
                    // Organization registration, email verification and resend (b2-2).
                    "/api/v1/public/**",
                    // Login, refresh and logout (b2-3). Refresh and logout are authenticated by the
                    // refresh-token cookie and a CSRF token, not by a bearer token.
                    "/api/v1/auth/login",
                    "/api/v1/auth/refresh",
                    "/api/v1/auth/logout",
                    // Email provider bounce/complaint events, authenticated by an HMAC signature
                    // (b1-4).
                    "/api/v1/webhooks/email/events",
                    // Container health checks; every other Actuator endpoint stays unexposed.
                    "/actuator/health",
                    "/actuator/health/**",
                    // The OpenAPI document, and Swagger UI when it is enabled.
                    "/v3/api-docs",
                    "/v3/api-docs/**",
                    "/swagger-ui.html",
                    "/swagger-ui/**");

    private static final RequestMatcher MATCHER =
            new OrRequestMatcher(
                    PATTERNS.stream()
                            .map(pattern -> PathPatternRequestMatcher.pathPattern(pattern))
                            .map(RequestMatcher.class::cast)
                            .toList());

    private static final List<PathPattern> PATH_PATTERNS =
            PATTERNS.stream().map(PathPatternParser.defaultInstance::parse).toList();

    private PublicEndpoints() {}

    /** Matches every public request. */
    public static RequestMatcher matcher() {
        return MATCHER;
    }

    /** Whether a full request path (for example from the OpenAPI document) is public. */
    public static boolean isPublicPath(String path) {
        PathContainer container = PathContainer.parsePath(path);
        return PATH_PATTERNS.stream().anyMatch(pattern -> pattern.matches(container));
    }
}
