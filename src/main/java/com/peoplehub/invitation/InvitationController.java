package com.peoplehub.invitation;

import com.peoplehub.security.principal.AuthenticatedPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inviting people into the caller's organization (b2-4, Spec 13.0). Authenticated; who may do what
 * is decided in {@link InvitationService}. HTTP and validation only.
 */
@RestController
public class InvitationController {

    private final InvitationService invitationService;

    public InvitationController(InvitationService invitationService) {
        this.invitationService = invitationService;
    }

    /** {@code POST /api/v1/admin/employees/invite}: Admin or Super Admin invites an Employee. */
    @PostMapping("/admin/employees/invite")
    @ResponseStatus(HttpStatus.CREATED)
    public InvitationResponse inviteEmployee(
            @AuthenticationPrincipal AuthenticatedPrincipal caller,
            @Valid @RequestBody InviteRequest request) {
        return invitationService.inviteEmployee(caller, request);
    }

    /** {@code POST /api/v1/super-admin/admins/invite}: Super Admin invites an Admin directly. */
    @PostMapping("/super-admin/admins/invite")
    @ResponseStatus(HttpStatus.CREATED)
    public InvitationResponse inviteAdmin(
            @AuthenticationPrincipal AuthenticatedPrincipal caller,
            @Valid @RequestBody InviteRequest request) {
        return invitationService.inviteAdmin(caller, request);
    }
}
