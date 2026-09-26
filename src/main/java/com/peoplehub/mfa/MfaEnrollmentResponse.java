package com.peoplehub.mfa;

/**
 * The answer to {@code POST /me/mfa/enroll} (b2-7, B2-7/6), returned once and sent with {@code
 * Cache-Control: no-store}.
 *
 * @param secret the TOTP secret in base32, for typing into an authenticator app by hand
 * @param otpauthUri the {@code otpauth://} URI the frontend draws as a QR code
 */
public record MfaEnrollmentResponse(String secret, String otpauthUri) {

    /** Never prints the secret or the URI. */
    @Override
    public String toString() {
        return "MfaEnrollmentResponse[...]";
    }
}
