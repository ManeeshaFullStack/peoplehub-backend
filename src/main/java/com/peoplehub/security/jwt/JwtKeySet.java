package com.peoplehub.security.jwt;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;

/**
 * The ES256 keys used to sign and verify access tokens (b2-3, B2-3/1, B2-3/2).
 *
 * <ul>
 *   <li>One <em>signing</em> key: a PKCS#8 PEM private key on curve P-256 plus its {@code kid}. Its
 *       public half is derived from it, so only the private key needs to be configured.
 *   <li>Any number of <em>previous</em> public keys, each with its own {@code kid}: tokens signed
 *       before a rotation stay valid until they expire (at most one access-token lifetime), then
 *       the old key can be removed.
 * </ul>
 *
 * <p>Anything wrong (missing, not PEM, not PKCS#8, wrong curve, a bad or duplicated {@code kid})
 * throws {@link IllegalStateException} and so stops the application at startup. The messages name
 * the setting, never its value, and the underlying parser exception is deliberately not attached:
 * no key material can reach a log line or an error report.
 *
 * <p>PEM is parsed leniently so it fits in an environment variable: the header and footer lines are
 * optional and every kind of whitespace (including literal {@code \n} sequences) is ignored.
 */
public final class JwtKeySet {

    static final String SIGNING_KEY_SETTING = "PEOPLEHUB_JWT_SIGNING_KEY";
    static final String SIGNING_KEY_ID_SETTING = "PEOPLEHUB_JWT_SIGNING_KEY_ID";
    static final String PREVIOUS_KEYS_SETTING = "PEOPLEHUB_JWT_PREVIOUS_PUBLIC_KEYS";

    /** A {@code kid} is logged and sent in every token header: a plain token, nothing else. */
    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private static final Pattern PEM_ARMOR = Pattern.compile("-----(BEGIN|END) [A-Z ]+-----");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+|\\\\n|\\\\r");

    private final ECKey signingKey;
    private final JWKSet verificationKeys;

    private JwtKeySet(ECKey signingKey, JWKSet verificationKeys) {
        this.signingKey = signingKey;
        this.verificationKeys = verificationKeys;
    }

    /**
     * Builds the key set from configuration values.
     *
     * @param signingKeyPem the PKCS#8 PEM private key, required
     * @param signingKeyId its {@code kid}, required
     * @param previousPublicKeys comma-separated {@code kid:PEM} public keys, or blank for none
     */
    public static JwtKeySet from(
            String signingKeyPem, String signingKeyId, String previousPublicKeys) {
        if (isBlank(signingKeyPem)) {
            throw new IllegalStateException(SIGNING_KEY_SETTING + " is required");
        }
        String kid = requireKeyId(signingKeyId, SIGNING_KEY_ID_SETTING);
        ECPrivateKey privateKey = parsePrivateKey(signingKeyPem);
        ECPublicKey publicKey = derivePublicKey(privateKey);

        ECKey signing =
                new ECKey.Builder(Curve.P_256, publicKey)
                        .privateKey(privateKey)
                        .keyID(kid)
                        .keyUse(KeyUse.SIGNATURE)
                        .algorithm(JWSAlgorithm.ES256)
                        .build();

        List<JWK> verification = new ArrayList<>();
        verification.add(signing.toPublicJWK());
        Set<String> kids = new HashSet<>(Set.of(kid));
        if (!isBlank(previousPublicKeys)) {
            for (String entry : previousPublicKeys.split(",")) {
                if (entry.isBlank()) {
                    continue;
                }
                int separator = entry.indexOf(':');
                if (separator < 0) {
                    throw new IllegalStateException(
                            PREVIOUS_KEYS_SETTING + " entries must be kid:PEM");
                }
                String previousKid =
                        requireKeyId(entry.substring(0, separator).strip(), PREVIOUS_KEYS_SETTING);
                if (!kids.add(previousKid)) {
                    throw new IllegalStateException(
                            PREVIOUS_KEYS_SETTING + " repeats a key id already in use");
                }
                verification.add(
                        new ECKey.Builder(
                                        Curve.P_256, parsePublicKey(entry.substring(separator + 1)))
                                .keyID(previousKid)
                                .keyUse(KeyUse.SIGNATURE)
                                .algorithm(JWSAlgorithm.ES256)
                                .build());
            }
        }
        return new JwtKeySet(signing, new JWKSet(verification));
    }

    /** The key new tokens are signed with (private). */
    public ECKey signingKey() {
        return signingKey;
    }

    /** Every key a token may be verified with (public only): the signing key and the previous. */
    public JWKSet verificationKeys() {
        return verificationKeys;
    }

    private static String requireKeyId(String value, String setting) {
        if (value == null || !KEY_ID.matcher(value.strip()).matches()) {
            throw new IllegalStateException(
                    setting + " needs a key id matching " + KEY_ID.pattern());
        }
        return value.strip();
    }

    private static ECPrivateKey parsePrivateKey(String pem) {
        if (pem.contains("EC PRIVATE KEY")) {
            throw new IllegalStateException(
                    SIGNING_KEY_SETTING
                            + " must be PKCS#8 (BEGIN PRIVATE KEY), not SEC1 (BEGIN EC PRIVATE KEY);"
                            + " convert it with: openssl pkcs8 -topk8 -nocrypt");
        }
        try {
            KeyFactory factory = KeyFactory.getInstance("EC");
            ECPrivateKey key =
                    (ECPrivateKey) factory.generatePrivate(new PKCS8EncodedKeySpec(derOf(pem)));
            requireP256(key.getParams(), SIGNING_KEY_SETTING);
            return key;
        } catch (GeneralSecurityException | IllegalArgumentException | ClassCastException e) {
            throw new IllegalStateException(
                    SIGNING_KEY_SETTING + " is not a valid PKCS#8 EC P-256 private key");
        }
    }

    private static ECPublicKey parsePublicKey(String pem) {
        try {
            PublicKey key =
                    KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(derOf(pem)));
            ECPublicKey ecKey = (ECPublicKey) key;
            requireP256(ecKey.getParams(), PREVIOUS_KEYS_SETTING);
            return ecKey;
        } catch (GeneralSecurityException | IllegalArgumentException | ClassCastException e) {
            throw new IllegalStateException(
                    PREVIOUS_KEYS_SETTING + " contains a key that is not an EC P-256 public key");
        }
    }

    /** Computes Q = d x G on P-256; the JDK has no public API to derive it from the private key. */
    private static ECPublicKey derivePublicKey(ECPrivateKey privateKey) {
        try {
            ECNamedCurveParameterSpec p256 = ECNamedCurveTable.getParameterSpec("secp256r1");
            org.bouncycastle.math.ec.ECPoint q =
                    p256.getG().multiply(privateKey.getS()).normalize();
            BigInteger x = q.getAffineXCoord().toBigInteger();
            BigInteger y = q.getAffineYCoord().toBigInteger();
            return (ECPublicKey)
                    KeyFactory.getInstance("EC")
                            .generatePublic(
                                    new ECPublicKeySpec(new ECPoint(x, y), privateKey.getParams()));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(
                    SIGNING_KEY_SETTING + " is not a valid PKCS#8 EC P-256 private key");
        }
    }

    private static void requireP256(java.security.spec.ECParameterSpec params, String setting) {
        if (!Curve.P_256.equals(Curve.forECParameterSpec(params))) {
            throw new IllegalStateException(setting + " must be on curve P-256 (ES256)");
        }
    }

    private static byte[] derOf(String pem) {
        String body = PEM_ARMOR.matcher(pem).replaceAll("");
        return Base64.getDecoder().decode(WHITESPACE.matcher(body).replaceAll(""));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
