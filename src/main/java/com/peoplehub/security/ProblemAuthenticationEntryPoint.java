package com.peoplehub.security;

import com.peoplehub.common.api.error.ApiProblemException;
import com.peoplehub.common.api.error.ProblemType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Answers a missing or rejected authentication with the standard problem body (b2-3, B2-3/16).
 *
 * <p>The body is built by {@code GlobalExceptionHandler}, through the MVC exception resolver, so a
 * 401 has exactly the same shape as every other error: correlation id, URN {@code instance}, no
 * request path. The detail is always the same generic sentence: an expired token, a bad signature,
 * a revoked session and no token at all are indistinguishable to the caller. {@code
 * WWW-Authenticate} names the scheme only, never an {@code error_description} (Spring's default
 * would say, for example, when the token expired).
 */
@Component
public class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

    static final String DETAIL = "Authentication is required to access this resource.";

    private final HandlerExceptionResolver resolver;

    public ProblemAuthenticationEntryPoint(
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException) {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        resolver.resolveException(
                request, response, null, new ApiProblemException(ProblemType.UNAUTHORIZED, DETAIL));
    }
}
