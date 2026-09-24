package com.peoplehub.invitation;

import java.time.Instant;

/**
 * What the invitation page shows before the invitee accepts (b2-4, B2-4/O4; Spec 2.1.5, 10.4.7):
 * the inviting organization and the role, and the invitee's own name and email. Nothing about any
 * other person or organization, and no ids.
 */
public record InvitationPreview(
        String organizationName, String role, String name, String email, Instant expiresAt) {}
