package com.peoplehub.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * The refresh-token and CSRF cookies (b2-3, B2-3/6, B2-3/11).
 *
 * <p>Both are {@code HttpOnly}, {@code Secure} and {@code SameSite=Strict}, scoped to {@value
 * #PATH} so the browser sends them only to login, refresh and logout, and never to JavaScript. The
 * {@code __Secure-} prefix makes the browser refuse them over plain HTTP. They expire with the
 * session's sliding expiry.
 */
@Component
class AuthCookies {

    static final String REFRESH_COOKIE = "__Secure-peoplehub_rt";
    static final String CSRF_COOKIE = "__Secure-peoplehub_csrf";
    static final String PATH = "/api/v1/auth";

    private final Clock clock;

    AuthCookies(Clock clock) {
        this.clock = clock;
    }

    /** Sets both cookies for a newly issued or rotated session. */
    void issue(HttpServletResponse response, SessionTokens tokens) {
        // Rounded up to whole seconds: the expiry was computed a moment ago, and a cookie that
        // outlives its token by under a second is harmless (the server decides), one that dies a
        // second early is not.
        long millis = Duration.between(clock.instant(), tokens.refreshExpiresAt()).toMillis();
        Duration maxAge = Duration.ofSeconds(Math.ceilDiv(millis, 1000L));
        add(response, cookie(REFRESH_COOKIE, tokens.refreshToken(), maxAge));
        add(response, cookie(CSRF_COOKIE, tokens.csrfToken(), maxAge));
    }

    /** Tells the browser to drop both cookies. */
    void clear(HttpServletResponse response) {
        add(response, cookie(REFRESH_COOKIE, "", Duration.ZERO));
        add(response, cookie(CSRF_COOKIE, "", Duration.ZERO));
    }

    Optional<String> refreshToken(HttpServletRequest request) {
        return read(request, REFRESH_COOKIE);
    }

    Optional<String> csrfToken(HttpServletRequest request) {
        return read(request, CSRF_COOKIE);
    }

    private static Optional<String> read(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())
                    && cookie.getValue() != null
                    && !cookie.getValue().isBlank()) {
                return Optional.of(cookie.getValue());
            }
        }
        return Optional.empty();
    }

    private static ResponseCookie cookie(String name, String value, Duration maxAge) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path(PATH)
                .maxAge(maxAge.isNegative() ? Duration.ZERO : maxAge)
                .build();
    }

    private static void add(HttpServletResponse response, ResponseCookie cookie) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
