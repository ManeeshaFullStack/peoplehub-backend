package com.peoplehub.security.principal;

import com.peoplehub.security.PublicEndpoints;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;

/**
 * Reads the bearer token from the {@code Authorization} header only (never a query parameter or a
 * form field, which can end up in logs), and ignores it on {@link PublicEndpoints} (b2-3). A stale
 * access token sent along with a login, refresh or logout call is therefore not an error: those
 * endpoints do not need one.
 */
public class PrivateEndpointBearerTokenResolver implements BearerTokenResolver {

    private final DefaultBearerTokenResolver delegate = new DefaultBearerTokenResolver();

    @Override
    public String resolve(HttpServletRequest request) {
        if (PublicEndpoints.matcher().matches(request)) {
            return null;
        }
        return delegate.resolve(request);
    }
}
