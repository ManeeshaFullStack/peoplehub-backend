package com.peoplehub.invitation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /public/invitations/{token}/accept} (b2-4, Spec 2.1.5, 13.0): only the invitee's own
 * new password. There is no organization, role, name or email field: the invitation fixes all of
 * them (D24, "cannot change org/role").
 */
public record AcceptInvitationRequest(
        @NotBlank @Size(max = 1024) String password,
        @NotBlank @Size(max = 1024) String confirmPassword) {

    @Override
    public String toString() {
        return "AcceptInvitationRequest[...]";
    }
}
