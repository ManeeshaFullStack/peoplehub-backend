package com.peoplehub.mfa.admin;

import com.peoplehub.security.principal.AuthenticatedPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Organization MFA administration (b2-7, B2-7/3, B2-7/12; Spec 3.2, 8.3, 13.0): the policy, the
 * selection of people and resetting another person's MFA. Needs an access token; the organization
 * is always the caller's own. HTTP only; the rules live in {@link MfaAdministrationService}.
 */
@RestController
public class MfaAdministrationController {

    private final MfaAdministrationService service;

    MfaAdministrationController(MfaAdministrationService service) {
        this.service = service;
    }

    /** {@code PUT /api/v1/organization/security/mfa-policy}: Super Admin, step-up. */
    @PutMapping("/organization/security/mfa-policy")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePolicy(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @Valid @RequestBody MfaPolicyRequest body,
            HttpServletRequest request) {
        service.changePolicy(principal, body.policy(), clientAddress(request));
    }

    /**
     * {@code PUT /api/v1/super-admin/employees/{id}/mfa-required}: selects a person for {@code
     * REQUIRED_FOR_SELECTED_USERS}. Super Admin, step-up.
     */
    @PutMapping("/super-admin/employees/{id}/mfa-required")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setRequired(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody MfaRequiredRequest body,
            HttpServletRequest request) {
        service.setRequired(principal, id, body.required(), clientAddress(request));
    }

    /** {@code POST /api/v1/admin/employees/{id}/mfa/reset}: Admin or Super Admin, step-up. */
    @PostMapping("/admin/employees/{id}/mfa/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reset(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable UUID id,
            HttpServletRequest request) {
        service.reset(principal, id, clientAddress(request));
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
