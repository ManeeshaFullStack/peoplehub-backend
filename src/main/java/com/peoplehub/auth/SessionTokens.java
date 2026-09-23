package com.peoplehub.auth;

import com.peoplehub.security.jwt.AccessTokenIssuer.IssuedAccessToken;
import java.time.Instant;
import java.util.UUID;

/**
 * Everything a successful login or refresh hands back (b2-3): the access token for the body, and
 * the raw refresh token and CSRF token for their cookies. The raw values exist only here, in
 * memory, on their way to the client; the database holds the refresh token's hash only.
 */
record SessionTokens(
        UUID sessionId,
        IssuedAccessToken accessToken,
        String refreshToken,
        Instant refreshExpiresAt,
        String csrfToken) {

    @Override
    public String toString() {
        return "SessionTokens[sessionId=" + sessionId + "]";
    }
}
