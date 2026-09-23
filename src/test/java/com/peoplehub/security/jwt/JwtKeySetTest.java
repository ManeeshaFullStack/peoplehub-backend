package com.peoplehub.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.JWK;
import com.peoplehub.support.TestJwtKeys;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * Loading the access-token keys (b2-3, B2-3/1, B2-3/2): valid configurations load, every invalid
 * one stops startup with a message that names the setting but never contains key material.
 */
class JwtKeySetTest {

    private static final KeyPair KEYS = TestJwtKeys.generate();
    private static final String PEM = TestJwtKeys.privatePem(KEYS);

    @Test
    void aPkcs8P256KeyLoadsAndItsPublicHalfIsDerived() throws Exception {
        JwtKeySet keys = JwtKeySet.from(PEM, "key-2026-09", "");

        assertThat(keys.signingKey().getKeyID()).isEqualTo("key-2026-09");
        assertThat(keys.signingKey().getAlgorithm()).isEqualTo(JWSAlgorithm.ES256);
        assertThat(keys.signingKey().getCurve()).isEqualTo(Curve.P_256);
        assertThat(keys.signingKey().isPrivate()).isTrue();
        // The derived public key is exactly the generated one.
        assertThat(keys.signingKey().toECPublicKey().getW())
                .isEqualTo(((java.security.interfaces.ECPublicKey) KEYS.getPublic()).getW());
        assertThat(keys.verificationKeys().getKeys())
                .singleElement()
                .satisfies(key -> assertThat(key.isPrivate()).isFalse());
    }

    @Test
    void thePemMayBeOneLineWithoutArmourOrWithEscapedNewlines() {
        String bare = Base64.getEncoder().encodeToString(KEYS.getPrivate().getEncoded());
        String escaped = PEM.replace("\n", "\\n");

        assertThat(JwtKeySet.from(bare, "k", null).signingKey().getKeyID()).isEqualTo("k");
        assertThat(JwtKeySet.from(escaped, "k", null).signingKey().getKeyID()).isEqualTo("k");
    }

    @Test
    void previousPublicKeysAreAddedForVerificationOnly() {
        KeyPair previous = TestJwtKeys.generate();
        KeyPair older = TestJwtKeys.generate();
        String config =
                "key-1:"
                        + TestJwtKeys.publicPem(previous)
                        + ", key-0:"
                        + TestJwtKeys.publicPem(older).replace("\n", "");

        JwtKeySet keys = JwtKeySet.from(PEM, "key-2", config);

        assertThat(keys.verificationKeys().getKeys())
                .extracting(JWK::getKeyID)
                .containsExactly("key-2", "key-1", "key-0");
        assertThat(keys.verificationKeys().getKeys()).noneMatch(JWK::isPrivate);
    }

    @Test
    void aMissingKeyOrKeyIdStopsStartup() {
        assertThatThrownBy(() -> JwtKeySet.from(null, "k", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("PEOPLEHUB_JWT_SIGNING_KEY is required");
        assertThatThrownBy(() -> JwtKeySet.from("  ", "k", null))
                .hasMessage("PEOPLEHUB_JWT_SIGNING_KEY is required");
        assertThatThrownBy(() -> JwtKeySet.from(PEM, null, null))
                .hasMessageStartingWith("PEOPLEHUB_JWT_SIGNING_KEY_ID needs a key id");
        assertThatThrownBy(() -> JwtKeySet.from(PEM, "has space", null))
                .hasMessageStartingWith("PEOPLEHUB_JWT_SIGNING_KEY_ID needs a key id");
    }

    @Test
    void anInvalidKeyStopsStartupWithoutEchoingIt() {
        String garbage = "-----BEGIN PRIVATE KEY-----\nbm90IGEga2V5\n-----END PRIVATE KEY-----";
        String notBase64 = "definitely-not-base64-secret-value";
        String publicKeyInstead = TestJwtKeys.publicPem(KEYS);

        for (String invalid : new String[] {garbage, notBase64, publicKeyInstead}) {
            assertThatThrownBy(() -> JwtKeySet.from(invalid, "k", null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(
                            "PEOPLEHUB_JWT_SIGNING_KEY is not a valid PKCS#8 EC P-256 private key")
                    .hasNoCause();
        }
    }

    @Test
    void aSec1KeyIsRefusedWithAConversionHint() {
        String sec1 = PEM.replace("PRIVATE KEY", "EC PRIVATE KEY");

        assertThatThrownBy(() -> JwtKeySet.from(sec1, "k", null))
                .hasMessageContaining("must be PKCS#8")
                .hasMessageContaining("openssl pkcs8 -topk8 -nocrypt")
                .hasNoCause();
    }

    @Test
    void aKeyOnAnotherCurveIsRefused() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp384r1"));
        String p384 = TestJwtKeys.privatePem(generator.generateKeyPair());

        assertThatThrownBy(() -> JwtKeySet.from(p384, "k", null))
                .hasMessage("PEOPLEHUB_JWT_SIGNING_KEY must be on curve P-256 (ES256)");
    }

    @Test
    void badPreviousKeysStopStartup() {
        String previous = TestJwtKeys.publicPem(TestJwtKeys.generate());

        assertThatThrownBy(() -> JwtKeySet.from(PEM, "k", previous))
                .hasMessage("PEOPLEHUB_JWT_PREVIOUS_PUBLIC_KEYS entries must be kid:PEM");
        assertThatThrownBy(() -> JwtKeySet.from(PEM, "k", "k:" + previous))
                .hasMessage("PEOPLEHUB_JWT_PREVIOUS_PUBLIC_KEYS repeats a key id already in use");
        assertThatThrownBy(() -> JwtKeySet.from(PEM, "k", "old:bm90IGEga2V5"))
                .hasMessage(
                        "PEOPLEHUB_JWT_PREVIOUS_PUBLIC_KEYS contains a key that is not an EC P-256"
                                + " public key")
                .hasNoCause();
    }

    @Test
    void noErrorMessageEverContainsKeyMaterial() {
        String body =
                PEM.lines().filter(line -> !line.startsWith("-----")).findFirst().orElseThrow();
        String[][] invalidConfigurations = {
            {PEM.replace(body, body.substring(0, 20) + "!!!!"), "k", null},
            {PEM, "bad kid", null},
            {PEM, "k", "old:" + PEM},
        };
        for (String[] config : invalidConfigurations) {
            assertThatThrownBy(() -> JwtKeySet.from(config[0], config[1], config[2]))
                    .satisfies(
                            e ->
                                    assertThat(e.getMessage())
                                            .doesNotContain(body.substring(0, 16))
                                            .doesNotContain("MIG"));
        }
    }
}
