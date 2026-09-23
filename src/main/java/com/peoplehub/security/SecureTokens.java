package com.peoplehub.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Random single-use tokens: a raw value to email once, and a hash of it to store (b2-2, B2-2/5) --
 * the same "hash at rest, never the raw value" discipline {@code employee_invitation.token_hash}
 * and {@code refresh_token.token_hash} already commit to in their schema. Only the JDK's own {@code
 * java.security}, no new Maven dependency, the same convention {@link
 * com.peoplehub.notification.email.WebhookSignatureVerifier} already uses.
 *
 * <p>{@link #generateRaw()} is hex-encoded on purpose: it is the raw value shown to a person (in an
 * email) and it must also fit {@code AuditDetails}/{@code EmailPayload}'s token pattern ({@code
 * [A-Za-z0-9._:-]{1,64}}), which hex satisfies and a URL-safe Base64 alphabet (with {@code +},
 * {@code /}, {@code =}) would not.
 */
@Component
public class SecureTokens {

    /** 256 bits of entropy, hex-encoded to 64 characters. */
    private static final int RAW_BYTES = 32;

    private static final String HASH_ALGORITHM = "SHA-256";

    private final SecureRandom random = new SecureRandom();

    /** A fresh random token, hex-encoded. Never store this value; store only {@link #hash}. */
    public String generateRaw() {
        byte[] bytes = new byte[RAW_BYTES];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** The hash of a raw token, for storage and for comparing a submitted token against it. */
    public String hash(String rawToken) {
        Objects.requireNonNull(rawToken, "rawToken");
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a JDK-mandatory algorithm: unreachable in practice.
            throw new IllegalStateException(HASH_ALGORITHM + " is not available", e);
        }
    }
}
