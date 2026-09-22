package com.peoplehub.notification.email;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * HMAC-SHA256 webhook signature verification (b1-4): only JDK crypto, no external dependency, no
 * provider-specific logic anywhere in this test either.
 */
class WebhookSignatureVerifierTest {

    private final WebhookSignatureVerifier verifier = new WebhookSignatureVerifier();

    private static String sign(String secret, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body));
    }

    @Test
    void aCorrectlySignedBodyIsValid() throws Exception {
        byte[] body =
                "{\"email\":\"jane@example.com\",\"eventType\":\"BOUNCE\"}"
                        .getBytes(StandardCharsets.UTF_8);
        String signature = sign("s3cret", body);

        assertThat(verifier.isValid("s3cret", body, signature)).isTrue();
    }

    @Test
    void aSignatureComputedWithTheWrongSecretIsInvalid() throws Exception {
        byte[] body = "{\"email\":\"jane@example.com\"}".getBytes(StandardCharsets.UTF_8);
        String signature = sign("wrong-secret", body);

        assertThat(verifier.isValid("s3cret", body, signature)).isFalse();
    }

    @Test
    void aTamperedBodyIsInvalidEvenWithAnOtherwiseCorrectSignature() throws Exception {
        byte[] originalBody = "{\"email\":\"jane@example.com\"}".getBytes(StandardCharsets.UTF_8);
        String signature = sign("s3cret", originalBody);
        byte[] tamperedBody =
                "{\"email\":\"attacker@example.com\"}".getBytes(StandardCharsets.UTF_8);

        assertThat(verifier.isValid("s3cret", tamperedBody, signature)).isFalse();
    }

    @Test
    void aNullOrBlankSecretIsAlwaysInvalidRegardlessOfTheSignature() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        String signature = sign("s3cret", body);

        assertThat(verifier.isValid(null, body, signature)).isFalse();
        assertThat(verifier.isValid("", body, signature)).isFalse();
        assertThat(verifier.isValid("   ", body, signature)).isFalse();
    }

    @Test
    void aMissingOrBlankSignatureIsInvalid() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

        assertThat(verifier.isValid("s3cret", body, null)).isFalse();
        assertThat(verifier.isValid("s3cret", body, "")).isFalse();
    }

    @Test
    void aSignatureThatIsNotValidHexIsInvalidNotAnError() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

        assertThat(verifier.isValid("s3cret", body, "not-hex-at-all!!")).isFalse();
    }

    @Test
    void anUppercaseHexSignatureIsAcceptedTheSameAsLowercase() throws Exception {
        byte[] body = "{\"email\":\"jane@example.com\"}".getBytes(StandardCharsets.UTF_8);
        String signature = sign("s3cret", body).toUpperCase();

        assertThat(verifier.isValid("s3cret", body, signature)).isTrue();
    }
}
