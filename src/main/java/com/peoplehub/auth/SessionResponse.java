package com.peoplehub.auth;

import java.time.Instant;
import java.util.UUID;

/**
 * One of the caller's own active sessions in {@code GET /me/sessions} (b2-6, B2-6/2). Never a
 * token, a token hash or an address.
 *
 * @param sessionId the session (refresh-token family) id, for {@code DELETE /me/sessions/{id}}
 * @param current whether this is the session making the request
 * @param deviceLabel a coarse browser and operating system, such as "Chrome on Windows" (B2-6/3)
 * @param createdAt when the session signed in
 * @param lastUsedAt when it was last refreshed (the sign-in time if never)
 * @param expiresAt when it ends unless it is refreshed again
 * @param absoluteExpiresAt when it ends at the latest, however often it is refreshed
 */
public record SessionResponse(
        UUID sessionId,
        boolean current,
        String deviceLabel,
        Instant createdAt,
        Instant lastUsedAt,
        Instant expiresAt,
        Instant absoluteExpiresAt) {}
