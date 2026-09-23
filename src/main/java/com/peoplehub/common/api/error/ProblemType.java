package com.peoplehub.common.api.error;

import java.net.URI;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * Catalogue of problem types returned in the {@code type} member of every error body (RFC 9457,
 * which supersedes RFC 7807 with the same shape; Spec 13). Types are URNs so no domain has to be
 * invented. Add an entry here when a new kind of error is first needed; do not invent ad-hoc types.
 */
public enum ProblemType {
    VALIDATION_ERROR("validation-error", "Validation failed", HttpStatus.BAD_REQUEST),
    INVALID_PAGE_REQUEST(
            "invalid-page-request",
            "Invalid pagination or sort parameters",
            HttpStatus.BAD_REQUEST),
    MALFORMED_REQUEST("malformed-request", "Malformed request", HttpStatus.BAD_REQUEST),
    NOT_FOUND("not-found", "Resource not found", HttpStatus.NOT_FOUND),
    METHOD_NOT_ALLOWED("method-not-allowed", "Method not allowed", HttpStatus.METHOD_NOT_ALLOWED),
    NOT_ACCEPTABLE("not-acceptable", "Not acceptable", HttpStatus.NOT_ACCEPTABLE),
    UNSUPPORTED_MEDIA_TYPE(
            "unsupported-media-type", "Unsupported media type", HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    // b1-4: the email bounce/complaint webhook is the first endpoint that needs a 401. General
    // 401/403 handling for authenticated endpoints is still owed from B2 (CLAUDE.md, "Owed from B4
    // onward"); this entry covers only the webhook's own shared-secret signature check.
    UNAUTHORIZED("unauthorized", "Authentication failed", HttpStatus.UNAUTHORIZED),
    // b2-2: organization registration's own "this identifier is already claimed" signals (the
    // organization name/login key is taken). Not a login/forgot-password/resend oracle -- those
    // stay generic and non-enumerating (D22); this is the requester's own chosen identifier
    // colliding with an existing one, the same kind of signal registration conventionally gives.
    CONFLICT("conflict", "Conflict", HttpStatus.CONFLICT),
    INTERNAL_ERROR("internal-error", "Unexpected error", HttpStatus.INTERNAL_SERVER_ERROR);

    private static final String URN_PREFIX = "urn:peoplehub:problem:";

    private final String code;
    private final String title;
    private final HttpStatus status;

    ProblemType(String code, String title, HttpStatus status) {
        this.code = code;
        this.title = title;
        this.status = status;
    }

    public String code() {
        return code;
    }

    public String title() {
        return title;
    }

    public HttpStatus status() {
        return status;
    }

    public URI uri() {
        return URI.create(URN_PREFIX + code);
    }

    /** The type used for framework-raised errors that only tell us the HTTP status. */
    static Optional<ProblemType> forStatus(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> Optional.of(MALFORMED_REQUEST);
            case 404 -> Optional.of(NOT_FOUND);
            case 405 -> Optional.of(METHOD_NOT_ALLOWED);
            case 406 -> Optional.of(NOT_ACCEPTABLE);
            case 415 -> Optional.of(UNSUPPORTED_MEDIA_TYPE);
            default -> status.is5xxServerError() ? Optional.of(INTERNAL_ERROR) : Optional.empty();
        };
    }
}
