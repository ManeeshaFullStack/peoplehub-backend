package com.peoplehub.security.jwt;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Access-token signing and verification (b2-3, B2-3/1-B2-3/4).
 *
 * <p>The decoder is built by hand rather than from a JWK Set URI, so it accepts exactly one
 * algorithm (ES256) and only the configured keys, looked up by {@code kid}: {@code alg=none}, an
 * HS256 token "signed" with a public key, and a token with an unknown {@code kid} all fail.
 * Nimbus's own claims check (which reads the system clock) is switched off; every time and claim
 * rule is one of the validators below, which use the application {@link Clock} so expiry is
 * testable.
 */
@Configuration(proxyBeanMethods = false)
public class JwtConfig {

    @Bean
    JwtKeySet jwtKeySet(
            @Value("${peoplehub.jwt.signing-key:#{null}}") String signingKey,
            @Value("${peoplehub.jwt.signing-key-id:#{null}}") String signingKeyId,
            @Value("${peoplehub.jwt.previous-public-keys:}") String previousPublicKeys) {
        return JwtKeySet.from(signingKey, signingKeyId, previousPublicKeys);
    }

    @Bean
    JwtEncoder jwtEncoder(JwtKeySet keys) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(keys.signingKey())));
    }

    @Bean
    JwtDecoder jwtDecoder(
            JwtKeySet keys,
            Clock clock,
            @Value("${peoplehub.jwt.issuer}") String issuer,
            @Value("${peoplehub.jwt.audience}") String audience,
            @Value("${peoplehub.jwt.clock-skew}") Duration clockSkew) {
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(
                new JWSVerificationKeySelector<>(
                        JWSAlgorithm.ES256, new ImmutableJWKSet<>(keys.verificationKeys())));
        processor.setJWTClaimsSetVerifier((claims, context) -> {});

        NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
        decoder.setJwtValidator(validator(clock, issuer, audience, clockSkew));
        return decoder;
    }

    @Bean
    AccessTokenIssuer accessTokenIssuer(
            JwtEncoder encoder,
            JwtKeySet keys,
            Clock clock,
            @Value("${peoplehub.jwt.issuer}") String issuer,
            @Value("${peoplehub.jwt.audience}") String audience,
            @Value("${peoplehub.jwt.access-token-ttl}") Duration ttl) {
        return new AccessTokenIssuer(
                encoder, keys.signingKey().getKeyID(), clock, issuer, audience, ttl);
    }

    static OAuth2TokenValidator<Jwt> validator(
            Clock clock, String issuer, String audience, Duration clockSkew) {
        JwtTimestampValidator timestamps = new JwtTimestampValidator(clockSkew);
        timestamps.setClock(clock);
        return new DelegatingOAuth2TokenValidator<>(
                List.of(
                        timestamps,
                        new JwtIssuerValidator(issuer),
                        new JwtClaimValidator<List<String>>(
                                "aud", aud -> aud != null && aud.contains(audience)),
                        // JwtTimestampValidator only checks exp when it is present: require it.
                        new JwtClaimValidator<Object>(
                                AccessTokenIssuer.CLAIM_EXPIRES_AT, exp -> exp != null),
                        // JwtTimestampValidator does not look at iat. Only this server issues
                        // tokens, so one "issued" in the future is forged or from a broken clock.
                        new JwtClaimValidator<Instant>(
                                AccessTokenIssuer.CLAIM_ISSUED_AT,
                                iat ->
                                        iat != null
                                                && !iat.isAfter(clock.instant().plus(clockSkew))),
                        new JwtClaimValidator<Object>(
                                AccessTokenIssuer.CLAIM_TOKEN_ID, jti -> jti != null),
                        new JwtClaimValidator<Object>(
                                AccessTokenIssuer.CLAIM_SUBJECT, JwtConfig::isUuid),
                        new JwtClaimValidator<Object>(
                                AccessTokenIssuer.CLAIM_ORGANIZATION, JwtConfig::isUuid),
                        new JwtClaimValidator<Object>(
                                AccessTokenIssuer.CLAIM_SESSION, JwtConfig::isUuid),
                        new JwtClaimValidator<Object>(
                                AccessTokenIssuer.CLAIM_ROLE, role -> role instanceof String)));
    }

    private static boolean isUuid(Object value) {
        if (!(value instanceof String text)) {
            return false;
        }
        try {
            return UUID.fromString(text).toString().equals(text);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
