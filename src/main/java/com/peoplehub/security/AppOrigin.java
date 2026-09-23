package com.peoplehub.security;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The one browser origin allowed to call the API with credentials (b2-3, B2-3/11): used for CORS
 * and for the Origin check on login, refresh and logout. Set with {@code
 * PEOPLEHUB_SECURITY_APP_ORIGIN}, for example {@code https://app.example.com}.
 *
 * <p>Optional: until the frontend exists there is nothing to allow, so an unset value means no
 * cross-origin browser access at all (fail closed). A value that is set but is not a bare origin
 * ({@code scheme://host[:port]}, no path, query or credentials) stops the application from starting
 * rather than being half-applied.
 */
@Component
public class AppOrigin {

    private final String origin;

    public AppOrigin(@Value("${peoplehub.security.app-origin:#{null}}") String configured) {
        this.origin = configured == null || configured.isBlank() ? null : validate(configured);
    }

    /** The configured origin, normalized to lower case, or empty when none is configured. */
    public Optional<String> value() {
        return Optional.ofNullable(origin);
    }

    /**
     * Whether a request's {@code Origin} header is acceptable: absent (a non-browser client), or
     * exactly the configured origin. A present header with no origin configured is refused.
     */
    public boolean permits(String originHeader) {
        if (originHeader == null) {
            return true;
        }
        return origin != null && origin.equals(originHeader.strip().toLowerCase(Locale.ROOT));
    }

    private static String validate(String configured) {
        String candidate = configured.strip().toLowerCase(Locale.ROOT);
        URI uri;
        try {
            uri = URI.create(candidate);
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
        boolean httpScheme = "https".equals(uri.getScheme()) || "http".equals(uri.getScheme());
        boolean bare =
                uri.getHost() != null
                        && uri.getUserInfo() == null
                        && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                        && uri.getRawQuery() == null
                        && uri.getRawFragment() == null;
        if (!httpScheme || !bare) {
            throw invalid();
        }
        return candidate;
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException(
                "peoplehub.security.app-origin (PEOPLEHUB_SECURITY_APP_ORIGIN) must be a bare"
                        + " origin such as https://app.example.com");
    }
}
