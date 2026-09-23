package com.peoplehub.security;

import com.peoplehub.common.api.error.ApiProblemException;
import com.peoplehub.common.api.error.ProblemType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.DefaultCorsProcessor;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Spring's CORS processing, except that a rejected request (an origin that is not the app origin)
 * gets the standard 403 problem body instead of Spring's plain-text "Invalid CORS request" (b2-3,
 * B2-3/11, B2-3/16): every error leaves as one RFC 9457 body. What is allowed is unchanged.
 */
class ProblemCorsProcessor extends DefaultCorsProcessor {

    static final String DETAIL = "The request could not be verified.";

    private final HandlerExceptionResolver resolver;

    /** The request being processed: {@link #rejectRequest} only receives the response. */
    private final ThreadLocal<HttpServletRequest> currentRequest = new ThreadLocal<>();

    ProblemCorsProcessor(HandlerExceptionResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public boolean processRequest(
            CorsConfiguration config, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        currentRequest.set(request);
        try {
            return super.processRequest(config, request, response);
        } finally {
            currentRequest.remove();
        }
    }

    @Override
    protected void rejectRequest(ServerHttpResponse response) throws IOException {
        HttpServletResponse servletResponse =
                ((ServletServerHttpResponse) response).getServletResponse();
        resolver.resolveException(
                currentRequest.get(),
                servletResponse,
                null,
                new ApiProblemException(ProblemType.FORBIDDEN, DETAIL));
    }
}
