package com.peoplehub.auth;

import com.peoplehub.common.api.error.ApiProblemException;
import com.peoplehub.common.api.error.ProblemType;
import com.peoplehub.security.AppOrigin;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * The cross-site request checks on the cookie-authenticated endpoints (b2-3, B2-3/11), and the
 * Origin check on forgot and reset password (b2-5, B2-5 R6).
 *
 * <ul>
 *   <li>Origin (login, refresh, logout, forgot and reset password): a request that carries an
 *       {@code Origin} header must come from the configured app origin. A request without one (a
 *       non-browser client) passes this check; the CSRF token is the main defence.
 *   <li>Double-submit CSRF token (refresh, logout): the {@value #CSRF_HEADER} header must equal the
 *       CSRF cookie, compared in constant time. A cross-site page can make the browser send the
 *       cookie but cannot read it, nor the token, which the API only ever returns in a response
 *       body to its own origin.
 * </ul>
 *
 * Failures are a 403 with a generic detail.
 */
@Component
public class CsrfOriginGuard {

    static final String CSRF_HEADER = "X-CSRF-Token";
    static final String DETAIL = "The request could not be verified.";

    private final AppOrigin appOrigin;

    CsrfOriginGuard(AppOrigin appOrigin) {
        this.appOrigin = appOrigin;
    }

    public void requireAllowedOrigin(HttpServletRequest request) {
        if (!appOrigin.permits(request.getHeader(HttpHeaders.ORIGIN))) {
            throw forbidden();
        }
    }

    void requireCsrfToken(HttpServletRequest request, String cookieValue) {
        String header = request.getHeader(CSRF_HEADER);
        if (header == null
                || cookieValue == null
                || !MessageDigest.isEqual(
                        header.getBytes(StandardCharsets.UTF_8),
                        cookieValue.getBytes(StandardCharsets.UTF_8))) {
            throw forbidden();
        }
    }

    private static ApiProblemException forbidden() {
        return new ApiProblemException(ProblemType.FORBIDDEN, DETAIL);
    }
}
