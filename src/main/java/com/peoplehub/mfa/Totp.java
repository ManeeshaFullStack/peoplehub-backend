package com.peoplehub.mfa;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Time-based one-time passwords, RFC 6238 over RFC 4226 (b2-7, B2-7/5): HMAC-SHA1, 6 digits, a
 * 30-second step, and one step of drift either way. The JDK's HMAC only, no dependency. These
 * values are what every common authenticator app assumes by default, so they are fixed here, not
 * settings.
 *
 * <p>Replay protection: {@link #verify} accepts a code only for a time step later than the last one
 * accepted for that person ({@code employee.mfa_totp_last_step}) and returns the step it matched,
 * which the caller then records. The same code, or an older one, never works twice.
 *
 * <p>Nothing here keeps or logs a secret or a code.
 */
public final class Totp {

    /** 160 bits, the RFC 4226 recommended length for an HMAC-SHA1 secret. */
    public static final int SECRET_BYTES = 20;

    static final int DIGITS = 6;
    static final long STEP_SECONDS = 30;
    static final int DRIFT_STEPS = 1;

    private static final String HMAC = "HmacSHA1";
    private static final Pattern CODE = Pattern.compile("\\d{" + DIGITS + "}");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {}

    /** A new random secret. Only ever stored encrypted ({@link MfaSecretCipher}). */
    public static byte[] newSecret() {
        byte[] secret = new byte[SECRET_BYTES];
        RANDOM.nextBytes(secret);
        return secret;
    }

    /** The secret as the base32 text an authenticator app accepts when typed in by hand. */
    public static String displaySecret(byte[] secret) {
        return Base32.encode(secret);
    }

    /** The time step an instant falls in. */
    public static long step(Instant instant) {
        return Math.floorDiv(instant.getEpochSecond(), STEP_SECONDS);
    }

    /**
     * Checks a submitted code against the current step and one step either side, skipping every
     * step at or before {@code lastAcceptedStep}. Spaces inside the code are ignored; anything else
     * that is not exactly six digits never matches.
     *
     * @return the matched step, to be recorded as the new last accepted step; empty if no match
     */
    public static OptionalLong verify(
            byte[] secret, String submittedCode, Instant now, Long lastAcceptedStep) {
        Objects.requireNonNull(secret, "secret");
        Objects.requireNonNull(now, "now");
        if (submittedCode == null) {
            return OptionalLong.empty();
        }
        String code = WHITESPACE.matcher(submittedCode).replaceAll("");
        if (!CODE.matcher(code).matches()) {
            return OptionalLong.empty();
        }
        byte[] submitted = code.getBytes(StandardCharsets.US_ASCII);
        long current = step(now);
        long matched = -1;
        // Every candidate step is computed and compared, match or not.
        for (long candidate = current - DRIFT_STEPS;
                candidate <= current + DRIFT_STEPS;
                candidate++) {
            if (candidate < 0 || (lastAcceptedStep != null && candidate <= lastAcceptedStep)) {
                continue;
            }
            byte[] expected = code(secret, candidate, DIGITS).getBytes(StandardCharsets.US_ASCII);
            if (MessageDigest.isEqual(expected, submitted) && matched < 0) {
                matched = candidate;
            }
        }
        return matched < 0 ? OptionalLong.empty() : OptionalLong.of(matched);
    }

    /**
     * The {@code otpauth://} URI an authenticator app reads from a QR code (Key Uri Format). The
     * backend returns the URI only; drawing the QR code is the frontend's job.
     *
     * @param issuer shown by the app as the account's provider, for example the application name
     * @param accountName shown by the app to tell accounts apart
     */
    public static String otpauthUri(String issuer, String accountName, byte[] secret) {
        String label = encode(issuer) + ":" + encode(accountName);
        return "otpauth://totp/"
                + label
                + "?secret="
                + Base32.encode(secret)
                + "&issuer="
                + encode(issuer)
                + "&algorithm=SHA1&digits="
                + DIGITS
                + "&period="
                + STEP_SECONDS;
    }

    /** The RFC 4226 HOTP value for a counter, zero-padded to {@code digits}. */
    static String code(byte[] secret, long counter, int digits) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret, HMAC));
            byte[] hash = mac.doFinal(longBytes(counter));
            int offset = hash[hash.length - 1] & 0x0F;
            int binary =
                    ((hash[offset] & 0x7F) << 24)
                            | ((hash[offset + 1] & 0xFF) << 16)
                            | ((hash[offset + 2] & 0xFF) << 8)
                            | (hash[offset + 3] & 0xFF);
            int modulus = (int) Math.pow(10, digits);
            String value = Integer.toString(binary % modulus);
            return "0".repeat(digits - value.length()) + value;
        } catch (GeneralSecurityException e) {
            // HmacSHA1 is a JDK-mandatory algorithm; no key material in the message.
            throw new IllegalStateException(HMAC + " is not available");
        }
    }

    private static byte[] longBytes(long value) {
        byte[] bytes = new byte[8];
        for (int i = 7; i >= 0; i--) {
            bytes[i] = (byte) (value & 0xFF);
            value >>>= 8;
        }
        return bytes;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
