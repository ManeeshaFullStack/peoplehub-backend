package com.peoplehub.security.principal;

import com.peoplehub.security.jwt.AccessTokenIssuer;
import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

/**
 * Turns a signature- and claim-verified access token into the caller's {@link
 * AuthenticatedPrincipal} (b2-3, B2-3/14), after checking with {@link PrincipalStatusQuery} that
 * the employee, the organization and the session are all still active. Any failure is an {@link
 * InvalidBearerTokenException}, which the security chain answers with the same generic 401 as a bad
 * signature or an expired token: the caller never learns which check failed.
 *
 * <p>Built by {@code SecurityConfig}, not a component: as a {@code Converter} bean Spring MVC would
 * also register it as a request-parameter converter.
 */
public class PrincipalJwtConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final String GENERIC = "Invalid access token";

    private final PrincipalStatusQuery statusQuery;

    public PrincipalJwtConverter(PrincipalStatusQuery statusQuery) {
        this.statusQuery = statusQuery;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        UUID employeeId = uuidClaim(jwt, AccessTokenIssuer.CLAIM_SUBJECT);
        UUID organizationId = uuidClaim(jwt, AccessTokenIssuer.CLAIM_ORGANIZATION);
        UUID sessionId = uuidClaim(jwt, AccessTokenIssuer.CLAIM_SESSION);

        String role =
                statusQuery
                        .activeRole(employeeId, organizationId, sessionId)
                        .orElseThrow(() -> new InvalidBearerTokenException(GENERIC));
        return new PrincipalAuthentication(
                new AuthenticatedPrincipal(employeeId, organizationId, role, sessionId));
    }

    private static UUID uuidClaim(Jwt jwt, String name) {
        Object value = jwt.getClaims().get(name);
        if (!(value instanceof String text)) {
            throw new InvalidBearerTokenException(GENERIC);
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            throw new InvalidBearerTokenException(GENERIC);
        }
    }
}
