package com.peoplehub.mfa;

/**
 * A stored TOTP secret could not be decrypted (b2-7, B2-7/7): unknown key id, malformed value, a
 * value belonging to another account, or tampering. One fixed message, no cause and no data, so
 * nothing about the key, the ciphertext or the secret can reach a log or an error report. It is an
 * internal error, never something a caller can influence into a different answer.
 */
public final class MfaSecretUnreadableException extends RuntimeException {

    public MfaSecretUnreadableException() {
        super("The stored MFA secret could not be decrypted", null, false, false);
    }
}
