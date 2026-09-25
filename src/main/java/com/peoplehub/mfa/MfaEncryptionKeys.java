package com.peoplehub.mfa;

import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * The AES-256 keys that encrypt TOTP secrets at rest (b2-7, B2-7/7), from the environment, in the
 * pattern of the access-token keys (B2-3/2).
 *
 * <ul>
 *   <li>One <em>current</em> key ({@code PEOPLEHUB_MFA_ENCRYPTION_KEY}, 32 bytes, base64) with its
 *       key id ({@code PEOPLEHUB_MFA_ENCRYPTION_KEY_ID}). Every new secret is encrypted with it.
 *   <li>Any number of <em>previous</em> keys ({@code PEOPLEHUB_MFA_PREVIOUS_ENCRYPTION_KEYS},
 *       comma-separated {@code kid:base64}), used to decrypt secrets written before a rotation,
 *       never to encrypt.
 * </ul>
 *
 * <p>Anything wrong (missing, not base64, not exactly 32 bytes, a constant value such as all zeros,
 * a bad or repeated key id) throws {@link IllegalStateException} and stops the application at
 * startup, whether or not any organization has MFA enabled. Messages name the setting, never its
 * value, and no parser exception is attached, so no key material reaches a log or an error report.
 */
public final class MfaEncryptionKeys {

    static final String KEY_SETTING = "PEOPLEHUB_MFA_ENCRYPTION_KEY";
    static final String KEY_ID_SETTING = "PEOPLEHUB_MFA_ENCRYPTION_KEY_ID";
    static final String PREVIOUS_KEYS_SETTING = "PEOPLEHUB_MFA_PREVIOUS_ENCRYPTION_KEYS";

    static final int KEY_BYTES = 32;

    /** A key id is stored in front of every ciphertext; it can never contain the ':' separator. */
    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final String AES = "AES";

    private final String currentKeyId;
    private final Map<String, SecretKey> keys;

    private MfaEncryptionKeys(String currentKeyId, Map<String, SecretKey> keys) {
        this.currentKeyId = currentKeyId;
        this.keys = Collections.unmodifiableMap(keys);
    }

    /**
     * Builds the key set from configuration values.
     *
     * @param currentKey the base64 current key, required
     * @param currentKeyId its key id, required
     * @param previousKeys comma-separated {@code kid:base64} keys for decryption only, or blank
     */
    public static MfaEncryptionKeys from(
            String currentKey, String currentKeyId, String previousKeys) {
        if (currentKey == null || currentKey.isBlank()) {
            throw new IllegalStateException(KEY_SETTING + " is required");
        }
        String kid = requireKeyId(currentKeyId, KEY_ID_SETTING);
        Map<String, SecretKey> keys = new LinkedHashMap<>();
        keys.put(kid, parseKey(currentKey, KEY_SETTING));
        if (previousKeys != null && !previousKeys.isBlank()) {
            for (String entry : previousKeys.split(",")) {
                if (entry.isBlank()) {
                    continue;
                }
                int separator = entry.indexOf(':');
                if (separator < 0) {
                    throw new IllegalStateException(
                            PREVIOUS_KEYS_SETTING + " entries must be kid:base64");
                }
                String previousKid =
                        requireKeyId(entry.substring(0, separator).strip(), PREVIOUS_KEYS_SETTING);
                if (keys.containsKey(previousKid)) {
                    throw new IllegalStateException(
                            PREVIOUS_KEYS_SETTING + " repeats a key id already in use");
                }
                keys.put(
                        previousKid,
                        parseKey(entry.substring(separator + 1), PREVIOUS_KEYS_SETTING));
            }
        }
        return new MfaEncryptionKeys(kid, keys);
    }

    /** The id of the key new secrets are encrypted with. */
    String currentKeyId() {
        return currentKeyId;
    }

    /** The key new secrets are encrypted with. */
    SecretKey currentKey() {
        return keys.get(currentKeyId);
    }

    /** The key with this id, current or previous, for decryption. */
    Optional<SecretKey> key(String keyId) {
        return Optional.ofNullable(keys.get(keyId));
    }

    @Override
    public String toString() {
        return "MfaEncryptionKeys[currentKeyId=" + currentKeyId + ", keys=" + keys.size() + "]";
    }

    private static String requireKeyId(String value, String setting) {
        if (value == null || !KEY_ID.matcher(value.strip()).matches()) {
            throw new IllegalStateException(
                    setting + " needs a key id matching " + KEY_ID.pattern());
        }
        return value.strip();
    }

    private static SecretKey parseKey(String base64, String setting) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(WHITESPACE.matcher(base64).replaceAll(""));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(setting + " must be base64");
        }
        if (bytes.length != KEY_BYTES) {
            throw new IllegalStateException(
                    setting + " must decode to exactly " + KEY_BYTES + " bytes (AES-256)");
        }
        if (isConstant(bytes)) {
            throw new IllegalStateException(
                    setting + " must be random (generate it with: openssl rand -base64 32)");
        }
        return new SecretKeySpec(bytes, AES);
    }

    private static boolean isConstant(byte[] bytes) {
        for (byte b : bytes) {
            if (b != bytes[0]) {
                return false;
            }
        }
        return true;
    }
}
