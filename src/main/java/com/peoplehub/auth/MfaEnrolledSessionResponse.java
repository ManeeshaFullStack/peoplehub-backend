package com.peoplehub.auth;

import java.util.List;

/**
 * The answer to a completed required enrollment at sign-in (b2-7, B2-7/8, B2-7/9): the session, as
 * in {@link TokenResponse}, plus the ten recovery codes, shown once. Sent with {@code
 * Cache-Control: no-store}; the refresh token travels only in its cookie.
 */
public record MfaEnrolledSessionResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        String csrfToken,
        List<String> recoveryCodes) {

    /** Never prints a token or a code. */
    @Override
    public String toString() {
        return "MfaEnrolledSessionResponse[tokenType=" + tokenType + "]";
    }
}
