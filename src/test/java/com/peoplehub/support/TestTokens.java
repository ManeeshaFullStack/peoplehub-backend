package com.peoplehub.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Hand-made access tokens for authentication tests (b2-3): well-formed ones signed with the test
 * key, and every kind of malformed or forged one the application must reject. Built with Nimbus
 * directly, not the application's issuer, so a test can set any claim or header it likes.
 */
public final class TestTokens {

    private TestTokens() {}

    /** The claims the application issues, valid for 15 minutes from {@code now}. */
    public static JWTClaimsSet.Builder validClaims(
            TestIdentities.Employee employee, UUID sessionId, Instant now) {
        return new JWTClaimsSet.Builder()
                .issuer("peoplehub")
                .audience("peoplehub-api")
                .subject(employee.id().toString())
                .claim("org", employee.organizationId().toString())
                .claim("role", employee.role())
                .claim("sid", sessionId.toString())
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(900)));
    }

    /** ES256 with the given key and {@code kid}. */
    public static String es256(KeyPair keys, String keyId, JWTClaimsSet claims) {
        try {
            SignedJWT jwt =
                    new SignedJWT(
                            new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(keyId).build(), claims);
            jwt.sign(new ECDSASigner((ECPrivateKey) keys.getPrivate()));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** ES256 with the application's own test signing key. */
    public static String signed(JWTClaimsSet claims) {
        return es256(TestJwtKeys.SIGNING, TestJwtKeys.KEY_ID, claims);
    }

    /** An unsigned token ({@code alg=none}). */
    public static String unsigned(JWTClaimsSet claims) {
        return new PlainJWT(claims).serialize();
    }

    /**
     * The classic algorithm-confusion attack: HS256 with the server's <em>public</em> key bytes as
     * the HMAC secret, and the server's {@code kid}.
     */
    public static String hs256WithPublicKey(JWTClaimsSet claims) {
        try {
            SignedJWT jwt =
                    new SignedJWT(
                            new JWSHeader.Builder(JWSAlgorithm.HS256)
                                    .keyID(TestJwtKeys.KEY_ID)
                                    .build(),
                            claims);
            jwt.sign(new MACSigner(TestJwtKeys.SIGNING.getPublic().getEncoded()));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
