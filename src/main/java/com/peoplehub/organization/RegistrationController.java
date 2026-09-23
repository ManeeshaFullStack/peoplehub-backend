package com.peoplehub.organization;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public organization registration and founder email verification (b2-2, Spec 2.1.3, 13.0). Served
 * under {@code /api/v1} automatically ({@code WebConfig}'s package predicate); this controller
 * declares only its own path.
 *
 * <p>No authentication anywhere in this controller: {@code register} creates a brand-new tenant
 * with no session before verification (D23), and {@code verify-email}/{@code resend-verification}
 * are intentionally public and non-enumerating (D22, Spec 13.0). Controller does HTTP + validation
 * only; every business rule lives in {@link RegistrationService} (Spec 5, layering).
 */
@RestController
@RequestMapping("/public/organizations")
public class RegistrationController {

    private final RegistrationService registrationService;

    public RegistrationController(RegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterOrganizationResponse register(
            @Valid @RequestBody RegisterOrganizationRequest request) {
        return registrationService.register(request);
    }

    @PostMapping("/verify-email")
    public VerifyEmailResponse verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        return registrationService.verifyEmail(request.token());
    }

    @PostMapping("/resend-verification")
    public ResendVerificationResponse resendVerification(
            @Valid @RequestBody ResendVerificationRequest request) {
        return registrationService.resendVerification(request);
    }
}
