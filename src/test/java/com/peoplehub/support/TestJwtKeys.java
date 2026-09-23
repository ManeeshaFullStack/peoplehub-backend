package com.peoplehub.support;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

/**
 * Throwaway ES256 keys for tests (b2-3, B2-3/2: no private key is ever committed). Generated once
 * per test JVM, so every cached application context signs and verifies with the same key; a new run
 * gets new keys. {@link #OTHER} is an unrelated key pair for forging tokens the application must
 * reject.
 */
public final class TestJwtKeys {

    public static final String KEY_ID = "test-key-1";

    /** The key the application under test signs with (via {@link TestJwtKeysEnvironment}). */
    public static final KeyPair SIGNING = generate();

    /** A key the application does not know. */
    public static final KeyPair OTHER = generate();

    private TestJwtKeys() {}

    public static ECPrivateKey signingPrivateKey() {
        return (ECPrivateKey) SIGNING.getPrivate();
    }

    public static ECPublicKey signingPublicKey() {
        return (ECPublicKey) SIGNING.getPublic();
    }

    /** PKCS#8 PEM of a private key, as the application expects it in the environment. */
    public static String privatePem(KeyPair keys) {
        return pem("PRIVATE KEY", keys.getPrivate().getEncoded());
    }

    /** X.509 (SubjectPublicKeyInfo) PEM of a public key. */
    public static String publicPem(KeyPair keys) {
        return pem("PUBLIC KEY", keys.getPublic().getEncoded());
    }

    public static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN "
                + type
                + "-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der)
                + "\n-----END "
                + type
                + "-----\n";
    }
}
