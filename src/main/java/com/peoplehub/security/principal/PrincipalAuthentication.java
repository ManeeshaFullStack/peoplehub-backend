package com.peoplehub.security.principal;

import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;

/**
 * The authentication stored in the security context for a verified access token (b2-3). Its
 * principal is the {@link AuthenticatedPrincipal}, so a controller receives it with
 * {@code @AuthenticationPrincipal}. It carries no authorities: b2-3 only distinguishes
 * authenticated from not (B2-3/19); roles and permissions arrive with b3-1. The raw token is not
 * kept.
 */
public class PrincipalAuthentication extends AbstractAuthenticationToken {

    private final AuthenticatedPrincipal principal;

    public PrincipalAuthentication(AuthenticatedPrincipal principal) {
        super(List.of());
        this.principal = principal;
        setAuthenticated(true);
    }

    @Override
    public AuthenticatedPrincipal getPrincipal() {
        return principal;
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public String getName() {
        return principal.employeeId().toString();
    }
}
