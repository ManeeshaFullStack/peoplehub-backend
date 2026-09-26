package com.peoplehub.auth;

import com.peoplehub.security.principal.AuthenticatedPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/me/step-up} (b2-7, B2-7/15; Spec 8.3): proves the caller again for this
 * session, so step-up protected actions are allowed for five minutes. Needs an access token; takes
 * no id from the request. HTTP only; the rules live in {@link StepUpService}.
 */
@RestController
public class StepUpController {

    private final StepUpService service;

    StepUpController(StepUpService service) {
        this.service = service;
    }

    @PostMapping("/me/step-up")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void stepUp(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @Valid @RequestBody StepUpRequest body,
            HttpServletRequest request) {
        service.stepUp(principal, body, clientAddress(request));
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
