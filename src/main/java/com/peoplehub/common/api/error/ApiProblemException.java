package com.peoplehub.common.api.error;

import java.util.List;
import java.util.Map;

/**
 * Signals an error that must be returned to the client in the standard problem body. Services throw
 * this (or a subclass) instead of building their own error responses, so no endpoint invents its
 * own error shape (Spec 13.2).
 *
 * <p>{@code detail} is shown to the client: keep it free of personal data and internal details.
 */
public class ApiProblemException extends RuntimeException {

    private final ProblemType type;
    private final List<ApiFieldError> fieldErrors;
    private final Map<String, Object> extensions;

    public ApiProblemException(ProblemType type, String detail) {
        this(type, detail, List.of(), Map.of());
    }

    public ApiProblemException(
            ProblemType type,
            String detail,
            List<ApiFieldError> fieldErrors,
            Map<String, Object> extensions) {
        super(detail);
        this.type = type;
        this.fieldErrors = List.copyOf(fieldErrors);
        this.extensions = Map.copyOf(extensions);
    }

    public ProblemType getType() {
        return type;
    }

    public String getDetail() {
        return getMessage();
    }

    public List<ApiFieldError> getFieldErrors() {
        return fieldErrors;
    }

    /** Extra members added to the problem body (for example {@code allowedSortFields}). */
    public Map<String, Object> getExtensions() {
        return extensions;
    }
}
