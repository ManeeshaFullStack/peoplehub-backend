package com.peoplehub.profile;

import com.peoplehub.auth.ChangePasswordRequest;
import com.peoplehub.auth.PasswordChangeService;
import com.peoplehub.security.principal.AuthenticatedPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/me} (b2-3, B2-3/19; b2-4, B2-4/O12; b2-5, B2-5/P9; Spec 13, 13.0): the signed-in
 * employee's own profile, welcome state and password. Needs an access token; takes no id from the
 * request at all.
 */
@RestController
@RequestMapping("/me")
public class MeController {

    private final MeService meService;
    private final PasswordChangeService passwordChangeService;

    public MeController(MeService meService, PasswordChangeService passwordChangeService) {
        this.meService = meService;
        this.passwordChangeService = passwordChangeService;
    }

    @GetMapping
    public MeResponse me(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return meService.me(principal);
    }

    /** {@code POST /api/v1/me/welcome/ack}: the "Get started" click on the welcome screen. */
    @PostMapping("/welcome/ack")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void acknowledgeWelcome(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
        meService.acknowledgeWelcome(principal);
    }

    /**
     * {@code POST /api/v1/me/password}: changes the caller's own password; this session stays
     * signed in and every other one ends.
     */
    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @Valid @RequestBody ChangePasswordRequest body,
            HttpServletRequest request) {
        passwordChangeService.change(principal, body, clientAddress(request));
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
