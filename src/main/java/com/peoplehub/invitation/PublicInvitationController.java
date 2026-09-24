package com.peoplehub.invitation;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The invitee's side of an invitation (b2-4, Spec 13.0): public, because the invitee has no account
 * yet; the invitation token in the path is the only credential. The request log records only the
 * route template, never the token (B0-4 D3), and error bodies never contain the path.
 */
@RestController
@RequestMapping("/public/invitations/{token}")
public class PublicInvitationController {

    private final InvitationAcceptanceService acceptanceService;

    public PublicInvitationController(InvitationAcceptanceService acceptanceService) {
        this.acceptanceService = acceptanceService;
    }

    /** Read-only: shows the organization and role (B2-4/O4). */
    @GetMapping("/preview")
    public InvitationPreview preview(@PathVariable String token) {
        return acceptanceService.preview(token);
    }

    /** Sets the invitee's own password and activates them (B2-4/O5). */
    @PostMapping("/accept")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void accept(
            @PathVariable String token, @Valid @RequestBody AcceptInvitationRequest request) {
        acceptanceService.accept(token, request);
    }
}
