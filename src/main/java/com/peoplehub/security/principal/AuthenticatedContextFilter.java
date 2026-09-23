package com.peoplehub.security.principal;

import com.peoplehub.common.logging.ActorId;
import com.peoplehub.common.logging.OrganizationId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Once a request is authenticated, records who it is in the logging context (b2-3, B2-3/18): the
 * actor id becomes the employee id and the organization id is added, so every log line and Sentry
 * event of the request carries them. Runs inside the security chain, right after bearer-token
 * authentication; {@code ActorIdFilter} clears both at the end of the request.
 *
 * <p>Deliberately not a Spring bean: as a bean it would also be registered as a servlet filter and
 * run a second time, outside the security chain.
 */
public class AuthenticatedContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.getPrincipal() instanceof AuthenticatedPrincipal principal) {
            ActorId.set(principal.employeeId().toString());
            OrganizationId.set(principal.organizationId());
        }
        chain.doFilter(request, response);
    }
}
