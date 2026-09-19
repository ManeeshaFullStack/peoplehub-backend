package com.peoplehub.common.api.error;

import com.peoplehub.common.api.correlation.CorrelationId;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * The single place where exceptions become HTTP error responses (Spec 13, 13.2). Every error,
 * whether raised by our code, by bean validation or by Spring MVC itself, leaves as an {@code
 * application/problem+json} body with {@code type}, {@code title}, {@code status}, {@code detail},
 * {@code instance} and {@code correlationId}, plus {@code fieldErrors} for validation failures.
 *
 * <p>Rules enforced here: no stack traces or exception text for unexpected errors, no echoing of
 * rejected values, and {@code instance} is a URN built from the correlation id (RFC 9457: it
 * identifies the occurrence). It is never the request path or query string: paths can carry ids or
 * email-approval tokens and queries can carry search terms, and error bodies end up in client logs
 * and error trackers (Spec 14.1, 15).
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String FIELD_ERRORS = "fieldErrors";
    private static final String INSTANCE_URN_PREFIX = "urn:peoplehub:request:";
    private static final Comparator<ApiFieldError> BY_FIELD_THEN_MESSAGE =
            Comparator.comparing(ApiFieldError::field).thenComparing(ApiFieldError::message);

    // ---- errors raised by our own code
    // -------------------------------------------------------------

    @ExceptionHandler(ApiProblemException.class)
    protected ResponseEntity<Object> handleApiProblem(ApiProblemException ex, WebRequest request) {
        ProblemDetail problem = problem(ex.getType(), ex.getDetail());
        if (!ex.getFieldErrors().isEmpty()) {
            problem.setProperty(FIELD_ERRORS, sorted(ex.getFieldErrors()));
        }
        ex.getExtensions().forEach(problem::setProperty);
        return handleExceptionInternal(
                ex, problem, new HttpHeaders(), ex.getType().status(), request);
    }

    /** Constraint violations raised outside the web layer, e.g. by {@code @Validated} services. */
    @ExceptionHandler(ConstraintViolationException.class)
    protected ResponseEntity<Object> handleConstraintViolation(
            ConstraintViolationException ex, WebRequest request) {
        List<ApiFieldError> errors = new ArrayList<>();
        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            errors.add(
                    new ApiFieldError(
                            leafName(violation.getPropertyPath()), violation.getMessage()));
        }
        return validationFailure(errors, ex, new HttpHeaders(), request);
    }

    /** Anything not handled elsewhere. The client gets no detail; the cause is logged. */
    @ExceptionHandler(Exception.class)
    protected ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        log.error("Unhandled exception while processing request", ex);
        ProblemDetail problem = problem(ProblemType.INTERNAL_ERROR, genericInternalDetail());
        return handleExceptionInternal(
                ex, problem, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
    }

    // ---- validation failures raised by Spring MVC
    // ---------------------------------------------------

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        List<ApiFieldError> errors = new ArrayList<>();
        ex.getBindingResult()
                .getFieldErrors()
                .forEach(e -> errors.add(new ApiFieldError(e.getField(), message(e))));
        ex.getBindingResult()
                .getGlobalErrors()
                .forEach(e -> errors.add(new ApiFieldError(e.getObjectName(), message(e))));
        return validationFailure(errors, ex, headers, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        List<ApiFieldError> errors = new ArrayList<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            if (result instanceof ParameterErrors parameterErrors) {
                parameterErrors
                        .getFieldErrors()
                        .forEach(e -> errors.add(new ApiFieldError(e.getField(), message(e))));
                parameterErrors
                        .getGlobalErrors()
                        .forEach(e -> errors.add(new ApiFieldError(e.getObjectName(), message(e))));
            } else {
                String name = parameterName(result.getMethodParameter());
                result.getResolvableErrors()
                        .forEach(e -> errors.add(new ApiFieldError(name, message(e))));
            }
        }
        return validationFailure(errors, ex, headers, request);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        String field = ex.getPropertyName() != null ? ex.getPropertyName() : "parameter";
        // The rejected value is deliberately not echoed.
        return validationFailure(
                List.of(new ApiFieldError(field, "has an invalid value")), ex, headers, request);
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(
            MissingServletRequestParameterException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        return validationFailure(
                List.of(new ApiFieldError(ex.getParameterName(), "is required")),
                ex,
                headers,
                request);
    }

    // ---- common response building
    // -------------------------------------------------------------------

    /**
     * Every response, including the ones Spring MVC builds itself for 404/405/406/415/malformed
     * JSON, passes through here, so they all get the same members and content type.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex,
            Object body,
            HttpHeaders headers,
            HttpStatusCode statusCode,
            WebRequest request) {
        ResponseEntity<Object> response =
                super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response == null || !(response.getBody() instanceof ProblemDetail problem)) {
            return response;
        }
        applyStandardMembers(problem, statusCode);

        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.putAll(response.getHeaders());
        responseHeaders.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        return new ResponseEntity<>(problem, responseHeaders, response.getStatusCode());
    }

    private ResponseEntity<Object> validationFailure(
            List<ApiFieldError> errors, Exception ex, HttpHeaders headers, WebRequest request) {
        ProblemDetail problem =
                problem(ProblemType.VALIDATION_ERROR, "One or more fields are invalid.");
        problem.setProperty(FIELD_ERRORS, sorted(errors));
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    private static ProblemDetail problem(ProblemType type, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(type.status(), detail);
        problem.setType(type.uri());
        problem.setTitle(type.title());
        return problem;
    }

    private static void applyStandardMembers(ProblemDetail problem, HttpStatusCode status) {
        // Framework-built problems still carry the default "about:blank" type: map them by status.
        // Problems we built ourselves already have a type and a deliberate detail, and keep them.
        if (problem.getType() == null || "about:blank".equals(problem.getType().toString())) {
            ProblemType.forStatus(status)
                    .ifPresent(
                            type -> {
                                problem.setType(type.uri());
                                problem.setTitle(type.title());
                            });
            // Never pass framework text through for these: it can echo the request path or
            // internals.
            if (status.value() == HttpStatus.NOT_FOUND.value()) {
                problem.setDetail("The requested resource was not found.");
            } else if (status.is5xxServerError()) {
                problem.setDetail(genericInternalDetail());
            }
        }
        String correlationId = CorrelationId.current();
        // Always set here: if left empty, Spring fills "instance" with the raw request path, which
        // can
        // contain ids or tokens (see the class comment).
        problem.setInstance(
                URI.create(
                        INSTANCE_URN_PREFIX + (correlationId != null ? correlationId : "unknown")));
        if (correlationId != null) {
            problem.setProperty("correlationId", correlationId);
        }
    }

    private static String genericInternalDetail() {
        return "An unexpected error occurred. Quote the correlation id when reporting it.";
    }

    private static List<ApiFieldError> sorted(List<ApiFieldError> errors) {
        return errors.stream().sorted(BY_FIELD_THEN_MESSAGE).toList();
    }

    private static String message(MessageSourceResolvable resolvable) {
        return Objects.requireNonNullElse(resolvable.getDefaultMessage(), "is invalid");
    }

    private static String leafName(Path path) {
        String name = null;
        for (Path.Node node : path) {
            name = node.getName();
        }
        return name != null ? name : "parameter";
    }

    private static String parameterName(MethodParameter parameter) {
        RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
        if (requestParam != null && !requestParam.name().isBlank()) {
            return requestParam.name();
        }
        PathVariable pathVariable = parameter.getParameterAnnotation(PathVariable.class);
        if (pathVariable != null && !pathVariable.name().isBlank()) {
            return pathVariable.name();
        }
        RequestHeader requestHeader = parameter.getParameterAnnotation(RequestHeader.class);
        if (requestHeader != null && !requestHeader.name().isBlank()) {
            return requestHeader.name();
        }
        String name = parameter.getParameterName();
        return name != null ? name : "arg" + parameter.getParameterIndex();
    }
}
