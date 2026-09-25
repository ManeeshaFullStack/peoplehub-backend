package com.peoplehub.mfa;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Encrypts and decrypts TOTP secrets for {@code employee.mfa_totp_secret} (b2-7, B2-7/7):
 * AES-256-GCM with a random 96-bit nonce and a 128-bit tag, using the JDK only.
 *
 * <p>Stored form: {@code keyId:base64(nonce || ciphertext || tag)}. The key id says which key (the
 * current one or a previous one, {@link MfaEncryptionKeys}) decrypts it, so keys can rotate without
 * rewriting every row at once.
 *
 * <p>Associated data binds a ciphertext to its account: a fixed purpose label plus the organization
 * and employee ids. A value copied onto another employee, or into another organization, fails
 * authentication and cannot be decrypted.
 *
 * <p>Every failure to decrypt (unknown key id, malformed value, wrong account, tampering) is the
 * same {@link MfaSecretUnreadableException}, with no cause and no data attached.
 */
public final class MfaSecretCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String AAD_PREFIX = "peoplehub:mfa-totp-secret:v1:";

    private final MfaEncryptionKeys keys;
    private final SecureRandom random = new SecureRandom();

    public MfaSecretCipher(MfaEncryptionKeys keys) {
        this.keys = Objects.requireNonNull(keys, "keys");
    }

    /** Encrypts a secret for one account with the current key. */
    public String encrypt(byte[] secret, UUID organizationId, UUID employeeId) {
        Objects.requireNonNull(secret, "secret");
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(
                    Cipher.ENCRYPT_MODE, keys.currentKey(), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(associatedData(organizationId, employeeId));
            byte[] sealed = cipher.doFinal(secret);
            byte[] stored =
                    ByteBuffer.allocate(nonce.length + sealed.length)
                            .put(nonce)
                            .put(sealed)
                            .array();
            return keys.currentKeyId() + ":" + Base64.getEncoder().encodeToString(stored);
        } catch (GeneralSecurityException e) {
            // AES-GCM is JDK-mandatory; never attach the cause (it could describe key material).
            throw new IllegalStateException("MFA secret encryption failed");
        }
    }

    /** Decrypts a stored secret for the account it was encrypted for. */
    public byte[] decrypt(String stored, UUID organizationId, UUID employeeId) {
        if (stored == null) {
            throw new MfaSecretUnreadableException();
        }
        int separator = stored.indexOf(':');
        if (separator <= 0) {
            throw new MfaSecretUnreadableException();
        }
        SecretKey key =
                keys.key(stored.substring(0, separator))
                        .orElseThrow(MfaSecretUnreadableException::new);
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(stored.substring(separator + 1));
        } catch (IllegalArgumentException e) {
            throw new MfaSecretUnreadableException();
        }
        if (bytes.length <= NONCE_BYTES + TAG_BITS / 8) {
            throw new MfaSecretUnreadableException();
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    key,
                    new GCMParameterSpec(TAG_BITS, bytes, 0, NONCE_BYTES));
            cipher.updateAAD(associatedData(organizationId, employeeId));
            return cipher.doFinal(bytes, NONCE_BYTES, bytes.length - NONCE_BYTES);
        } catch (GeneralSecurityException e) {
            throw new MfaSecretUnreadableException();
        }
    }

    private static byte[] associatedData(UUID organizationId, UUID employeeId) {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(employeeId, "employeeId");
        return (AAD_PREFIX + organizationId + ":" + employeeId).getBytes(StandardCharsets.UTF_8);
    }
}
