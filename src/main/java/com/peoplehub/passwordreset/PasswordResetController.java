package com.peoplehub.passwordreset;

import com.peoplehub.auth.CsrfOriginGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/auth/forgot-password} and {@code /reset-password} (b2-5, Spec 8.2, 13.0). Public:
 * the person has no session; the reset code from the email is the only credential for a reset.
 * Neither endpoint uses cookies, so neither needs the CSRF token; both get the same Origin check as
 * login (B2-5 R6). HTTP only; the rules live in {@link PasswordResetService}.
 */
@RestController
@RequestMapping("/auth")
public class PasswordResetController {

    private final PasswordResetService service;
    private final CsrfOriginGuard guard;

    public PasswordResetController(PasswordResetService service, CsrfOriginGuard guard) {
        this.service = service;
        this.guard = guard;
    }

    /** Always 202 with the same message, whether or not an email was sent. */
    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ForgotPasswordResponse forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest body, HttpServletRequest request) {
        guard.requireAllowedOrigin(request);
        return service.forgot(body, clientAddress(request));
    }

    /** Sets the new password and ends every session; the person then signs in again. */
    @PostMapping("/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(
            @Valid @RequestBody ResetPasswordRequest body, HttpServletRequest request) {
        guard.requireAllowedOrigin(request);
        service.reset(body, clientAddress(request));
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
