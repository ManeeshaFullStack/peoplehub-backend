package com.peoplehub.mfa;

import java.io.ByteArrayOutputStream;
import java.util.Locale;
import java.util.Optional;

/**
 * RFC 4648 base32 without padding (b2-7): the alphabet authenticator apps expect for a TOTP secret,
 * and the one recovery codes are written in. The JDK has no base32, and this is small enough not to
 * justify a dependency (the {@code WebhookSignatureVerifier} convention).
 */
final class Base32 {

    static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Base32() {}

    /** Encodes bytes as upper-case base32 with no padding. */
    static String encode(byte[] bytes) {
        StringBuilder out = new StringBuilder((bytes.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                out.append(ALPHABET.charAt((buffer >>> (bits - 5)) & 0x1F));
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(ALPHABET.charAt((buffer << (5 - bits)) & 0x1F));
        }
        return out.toString();
    }

    /**
     * Decodes unpadded base32, ignoring case. Empty when the text contains anything outside the
     * alphabet or has a length no encoder produces.
     */
    static Optional<byte[]> decode(String text) {
        String upper = text.toUpperCase(Locale.ROOT);
        int remainder = upper.length() % 8;
        if (remainder == 1 || remainder == 3 || remainder == 6) {
            return Optional.empty();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(upper.length() * 5 / 8);
        int buffer = 0;
        int bits = 0;
        for (int i = 0; i < upper.length(); i++) {
            int value = ALPHABET.indexOf(upper.charAt(i));
            if (value < 0) {
                return Optional.empty();
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >>> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return Optional.of(out.toByteArray());
    }
}
