package com.peoplehub.organization;

import com.peoplehub.security.principal.AuthenticatedPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/organization/onboarding/complete} (b2-7, B2-7/21; Spec 13.0): the founder
 * finishes first-time setup. Super Admin only, idempotent. HTTP only; the rules live in {@link
 * OnboardingService}. The organization is always the caller's own.
 */
@RestController
public class OnboardingController {

    private final OnboardingService service;

    OnboardingController(OnboardingService service) {
        this.service = service;
    }

    @PostMapping("/organization/onboarding/complete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void complete(
            @AuthenticationPrincipal AuthenticatedPrincipal caller, HttpServletRequest request) {
        service.complete(caller, clientAddress(request));
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
