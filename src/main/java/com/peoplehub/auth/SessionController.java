package com.peoplehub.auth;

import com.peoplehub.common.api.paging.PageParams;
import com.peoplehub.common.api.paging.PageQuery;
import com.peoplehub.common.api.paging.PageResponse;
import com.peoplehub.security.principal.AuthenticatedPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/me/sessions} (b2-6, B2-6/2, 4, 5; Spec 8.2, 13): the caller's own sessions. Needs
 * an access token; the employee always comes from it, never from the request. HTTP only; the rules
 * live in {@link SessionService}.
 */
@RestController
@RequestMapping("/me/sessions")
public class SessionController {

    private final SessionService sessionService;
    private final AuthCookies cookies;

    SessionController(SessionService sessionService, AuthCookies cookies) {
        this.sessionService = sessionService;
        this.cookies = cookies;
    }

    @GetMapping
    public PageResponse<SessionResponse> list(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PageParams(
                            defaultSort = "lastUsedAt,desc",
                            sortable = {"createdAt", "lastUsedAt"})
                    PageQuery query) {
        return sessionService.list(principal, query);
    }

    /**
     * Ends one of the caller's sessions. Ending the current one is a logout from this device: its
     * cookies are cleared too, as {@code POST /auth/logout} does.
     */
    @DeleteMapping("/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable UUID sessionId,
            HttpServletRequest request,
            HttpServletResponse response) {
        if (sessionService.revoke(principal, sessionId, clientAddress(request))) {
            cookies.clear(response);
        }
    }

    /** Ends every session of the caller except this one (the spec's "revoke all others"). */
    @PostMapping("/revoke-others")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeOthers(
            @AuthenticationPrincipal AuthenticatedPrincipal principal, HttpServletRequest request) {
        sessionService.revokeOthers(principal, clientAddress(request));
    }

    /** The direct peer's address; forwarded headers are not trusted yet (B2-3/15). */
    private static InetAddress clientAddress(HttpServletRequest request) {
        try {
            String address = request.getRemoteAddr();
            return address == null ? null : InetAddress.getByName(address);
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
