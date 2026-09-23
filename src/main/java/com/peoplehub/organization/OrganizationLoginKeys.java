package com.peoplehub.organization;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Derives {@code organization.login_key_normalized} (V8) from the founder-submitted organization
 * name at registration (b2-2, Spec 2.1.1, 2.1.3). Lower-cases, trims, and collapses every run of
 * non-alphanumeric characters into a single {@code -}, matching the table's own {@code
 * ck_organization_login_key_is_normalized} CHECK ({@code login_key_normalized = lower(btrim(...))})
 * and keeping the result within its {@code VARCHAR(200)} column.
 *
 * <p>A collision on the result (two founders independently arriving at the same slug, or one
 * retrying with the same name) is not handled here: {@code organization}'s own {@code
 * uq_organization_login_key} unique constraint is the single source of truth for that, and {@code
 * RegistrationService} maps the resulting {@code DuplicateKeyException} to a 409 (B2-2 final
 * decisions: "use existing organization login key uniqueness for duplicate organization
 * protection").
 */
public final class OrganizationLoginKeys {

    private static final Pattern NON_ALPHANUMERIC_RUN = Pattern.compile("[^a-z0-9]+");

    /** organization.login_key_normalized is VARCHAR(200) (V8). */
    private static final int MAX_LENGTH = 200;

    private OrganizationLoginKeys() {}

    public static String normalize(String organizationName) {
        if (organizationName == null) {
            throw new IllegalArgumentException("organizationName is required");
        }
        String lower = organizationName.toLowerCase(Locale.ROOT).strip();
        String slug = NON_ALPHANUMERIC_RUN.matcher(lower).replaceAll("-");
        slug = stripEdgeDashes(slug);
        if (slug.length() > MAX_LENGTH) {
            slug = stripEdgeDashes(slug.substring(0, MAX_LENGTH));
        }
        if (slug.isEmpty()) {
            throw new IllegalArgumentException(
                    "Organization name must contain at least one letter or digit");
        }
        return slug;
    }

    private static String stripEdgeDashes(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '-') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == '-') {
            end--;
        }
        return value.substring(start, end);
    }
}
