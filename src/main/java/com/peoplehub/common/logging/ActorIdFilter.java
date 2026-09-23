package com.peoplehub.common.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts an actor id in MDC for every request, right after the correlation id (Spec 14.1). It seeds
 * {@value ActorId#ANONYMOUS}; the authentication step (b2-3) overwrites it with the employee id
 * from the token and adds the {@link OrganizationId}. This filter owns the clean-up of both, so
 * neither value outlives the request.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class ActorIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        ActorId.set(ActorId.ANONYMOUS);
        try {
            chain.doFilter(request, response);
        } finally {
            ActorId.clear();
            OrganizationId.clear();
        }
    }
}
