package com.peoplehub.security.jwt;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

/**
 * Issues access tokens (b2-3, B2-3/1, B2-3/3, B2-3/4): ES256, the active {@code kid} in the header,
 * and exactly the approved claims. No email, name or other personal data is ever put in a token.
 * The times come from the application {@link Clock}.
 */
public class AccessTokenIssuer {

    public static final String CLAIM_SUBJECT = "sub";
    public static final String CLAIM_ORGANIZATION = "org";
    public static final String CLAIM_ROLE = "role";
    public static final String CLAIM_SESSION = "sid";
    public static final String CLAIM_TOKEN_ID = "jti";
    public static final String CLAIM_ISSUED_AT = "iat";
    public static final String CLAIM_EXPIRES_AT = "exp";

    private final JwtEncoder encoder;
    private final String keyId;
    private final Clock clock;
    private final String issuer;
    private final String audience;
    private final Duration ttl;

    AccessTokenIssuer(
            JwtEncoder encoder,
            String keyId,
            Clock clock,
            String issuer,
            String audience,
            Duration ttl) {
        this.encoder = encoder;
        this.keyId = keyId;
        this.clock = clock;
        this.issuer = issuer;
        this.audience = audience;
        this.ttl = ttl;
    }

    /**
     * Issues a token for one employee and one refresh-token family (session).
     *
     * @param role the role at issue time; informational only, authorization reads it from the
     *     database on every request (B2-3/14)
     */
    public IssuedAccessToken issue(
            UUID employeeId, UUID organizationId, String role, UUID sessionId) {
        Objects.requireNonNull(employeeId, "employeeId");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(sessionId, "sessionId");

        Instant issuedAt = clock.instant();
        JwtClaimsSet claims =
                JwtClaimsSet.builder()
                        .issuer(issuer)
                        .audience(List.of(audience))
                        .subject(employeeId.toString())
                        .claim(CLAIM_ORGANIZATION, organizationId.toString())
                        .claim(CLAIM_ROLE, role)
                        .claim(CLAIM_SESSION, sessionId.toString())
                        .id(UUID.randomUUID().toString())
                        .issuedAt(issuedAt)
                        .expiresAt(issuedAt.plus(ttl))
                        .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.ES256).keyId(keyId).build();
        String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedAccessToken(value, ttl.toSeconds());
    }

    /** A signed access token and its lifetime in seconds (for the {@code expiresIn} field). */
    public record IssuedAccessToken(String value, long expiresInSeconds) {

        @Override
        public String toString() {
            // Never print the token itself.
            return "IssuedAccessToken[expiresInSeconds=" + expiresInSeconds + "]";
        }
    }
}
