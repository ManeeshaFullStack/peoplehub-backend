package com.peoplehub.auth;

/**
 * The answer to a correct password when MFA is part of the sign-in (b2-7, B2-7/9): no session yet,
 * and no cookie. Sent with {@code Cache-Control: no-store}.
 *
 * @param mfaRequired {@code CHALLENGE} (prove a TOTP or recovery code at {@code
 *     /auth/mfa/challenge}) or {@code ENROLL} (enroll at {@code /auth/mfa/enroll}, then {@code
 *     /auth/mfa/enroll/confirm})
 * @param challengeToken single use; valid for 5 minutes ({@code CHALLENGE}) or 10 ({@code ENROLL})
 */
public record MfaChallengeResponse(String mfaRequired, String challengeToken) {

    /** Never prints the token. */
    @Override
    public String toString() {
        return "MfaChallengeResponse[mfaRequired=" + mfaRequired + "]";
    }
}
