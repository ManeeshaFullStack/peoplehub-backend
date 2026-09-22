package com.peoplehub.notification.email;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * HMAC-SHA256 signature verification for the inbound bounce/complaint webhook (b1-4), using only
 * the JDK's own {@code javax.crypto}/{@code java.security} -- no new Maven dependency, no provider
 * SDK. This is the same shared-secret HMAC convention most SMTP/ESP providers use for webhook
 * authenticity, so nothing here is Brevo-specific (or specific to any other provider): swapping
 * providers later is a configuration change, not a code change, the same discipline already applied
 * to {@code SmtpEmailSender}.
 *
 * <p>Comparison is constant-time ({@link MessageDigest#isEqual}), so a timing side-channel cannot
 * help an attacker guess a valid signature one byte at a time.
 */
@Component
public class WebhookSignatureVerifier {

    private static final String ALGORITHM = "HmacSHA256";

    /**
     * @param secret the shared secret ({@code peoplehub.email.webhook-secret}); {@code null} or
     *     blank always fails closed -- the same "required, no fake default" treatment as {@code
     *     peoplehub.email.from-address}
     * @param body the exact raw request bytes the signature was computed over (never a
     *     re-serialized copy: re-encoding can differ from the bytes actually received and silently
     *     break verification)
     * @param providedSignatureHex the signature header value, lowercase or uppercase hex
     */
    public boolean isValid(String secret, byte[] body, String providedSignatureHex) {
        if (secret == null
                || secret.isBlank()
                || body == null
                || providedSignatureHex == null
                || providedSignatureHex.isBlank()) {
            return false;
        }
        byte[] provided;
        try {
            provided = HexFormat.of().parseHex(providedSignatureHex.strip());
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(hmac(secret, body), provided);
    }

    private static byte[] hmac(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return mac.doFinal(body);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            // HmacSHA256 is a JDK-mandatory algorithm and the key is never empty here (checked
            // above): unreachable in practice. Fail closed rather than let either propagate as a
            // 500.
            return new byte[0];
        }
    }
}
