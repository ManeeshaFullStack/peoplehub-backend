package com.peoplehub.security;

import com.peoplehub.common.api.error.ApiProblemException;
import com.peoplehub.common.api.error.ProblemType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Answers an authenticated but forbidden request with the standard problem body (b2-3, B2-3/16),
 * built by {@code GlobalExceptionHandler} the same way as {@link ProblemAuthenticationEntryPoint}.
 * b2-3 has no role rules (B2-3/19), so this is the fail-safe for anything Spring Security itself
 * denies.
 */
@Component
public class ProblemAccessDeniedHandler implements AccessDeniedHandler {

    static final String DETAIL = "You are not allowed to perform this action.";

    private final HandlerExceptionResolver resolver;

    public ProblemAccessDeniedHandler(
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException) {
        resolver.resolveException(
                request, response, null, new ApiProblemException(ProblemType.FORBIDDEN, DETAIL));
    }
}
