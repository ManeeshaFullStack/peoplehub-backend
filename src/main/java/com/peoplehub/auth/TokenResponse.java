package com.peoplehub.auth;

/**
 * The body of a successful login or refresh (b2-3, B2-3/3, B2-3/11). The refresh token itself is
 * never in the body: it travels only in its {@code HttpOnly} cookie.
 *
 * @param accessToken the ES256 access token, to be kept in memory by the client
 * @param tokenType always {@code Bearer}
 * @param expiresIn the access token's lifetime in seconds
 * @param csrfToken the value to send back in {@code X-CSRF-Token} on refresh and logout; returned
 *     here because the frontend's origin cannot read the API's cookies (D10)
 */
public record TokenResponse(
        String accessToken, String tokenType, long expiresIn, String csrfToken) {

    @Override
    public String toString() {
        return "TokenResponse[tokenType=" + tokenType + ", expiresIn=" + expiresIn + "]";
    }
}
