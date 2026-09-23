package com.peoplehub.invitation;

import java.time.Instant;
import java.util.UUID;

/**
 * A created (or re-issued) invitation (b2-4). Never contains the invitation token: that exists only
 * in the invitation email. {@code invitationId} is what resend and revoke take (B2-4/O13).
 */
public record InvitationResponse(
        UUID invitationId, UUID employeeId, String role, Instant expiresAt) {}
