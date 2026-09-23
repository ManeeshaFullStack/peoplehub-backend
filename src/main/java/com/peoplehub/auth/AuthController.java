package com.peoplehub.auth;

import com.peoplehub.common.api.error.ApiProblemException;
import com.peoplehub.common.api.error.ProblemType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/auth/login}, {@code /refresh} and {@code /logout} (b2-3, B2-3 decisions; Spec 8.1,
 * 13.0). Public in the security chain: login needs no token, and refresh and logout are
 * authenticated by the refresh-token cookie plus the CSRF token instead of a bearer token. HTTP,
 * cookies and the cross-site checks only; the rules live in {@link LoginService} and {@link
 * RefreshTokenService}.
 *
 * <p>Every failure of a kind is indistinguishable from the others of that kind: one 401 for any
 * failed login, one 401 for any refresh that cannot be honoured, one 403 for any failed Origin or
 * CSRF check.
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    static final String LOGIN_FAILED = "We couldn't sign you in with those details.";
    static final String SESSION_ENDED = "Your session has ended. Please sign in again.";

    private final LoginService loginService;
    private final RefreshTokenService refreshTokenService;
    private final AuthCookies cookies;
    private final CsrfOriginGuard guard;

    AuthController(
            LoginService loginService,
            RefreshTokenService refreshTokenService,
            AuthCookies cookies,
            CsrfOriginGuard guard) {
        this.loginService = loginService;
        this.refreshTokenService = refreshTokenService;
        this.cookies = cookies;
        this.guard = guard;
    }

    @PostMapping("/login")
    public TokenResponse login(
            @Valid @RequestBody LoginRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        guard.requireAllowedOrigin(request);
        SessionTokens tokens =
                loginService
                        .login(body, clientAddress(request))
                        .orElseThrow(
                                () ->
                                        new ApiProblemException(
                                                ProblemType.UNAUTHORIZED, LOGIN_FAILED));
        cookies.issue(response, tokens);
        return tokenResponse(tokens);
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(HttpServletRequest request, HttpServletResponse response) {
        guard.requireAllowedOrigin(request);
        Optional<String> refreshToken = cookies.refreshToken(request);
        if (refreshToken.isEmpty()) {
            throw sessionEnded(response);
        }
        guard.requireCsrfToken(request, cookies.csrfToken(request).orElse(null));
        SessionTokens tokens =
                refreshTokenService
                        .refresh(refreshToken.get(), clientAddress(request))
                        .orElseThrow(() -> sessionEnded(response));
        cookies.issue(response, tokens);
        return tokenResponse(tokens);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        guard.requireAllowedOrigin(request);
        Optional<String> refreshToken = cookies.refreshToken(request);
        if (refreshToken.isPresent()) {
            guard.requireCsrfToken(request, cookies.csrfToken(request).orElse(null));
            refreshTokenService.logout(refreshToken.get(), clientAddress(request));
        }
        cookies.clear(response);
    }

    private ApiProblemException sessionEnded(HttpServletResponse response) {
        // The browser should stop sending a token that can never work again.
        cookies.clear(response);
        return new ApiProblemException(ProblemType.UNAUTHORIZED, SESSION_ENDED);
    }

    private static TokenResponse tokenResponse(SessionTokens tokens) {
        return new TokenResponse(
                tokens.accessToken().value(),
                "Bearer",
                tokens.accessToken().expiresInSeconds(),
                tokens.csrfToken());
    }

    /**
     * The address of the direct peer. Forwarded headers are not trusted until the hosting (and so
     * the proxy in front of the application) is decided (B2-3/15, Spec 19).
     */
    private static InetAddress clientAddress(HttpServletRequest request) {
        try {
            String address = request.getRemoteAddr();
            return address == null ? null : InetAddress.getByName(address);
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
