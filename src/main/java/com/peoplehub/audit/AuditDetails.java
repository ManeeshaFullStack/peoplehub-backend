package com.peoplehub.audit;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The structured, deliberately supplied metadata of one audit row (B0-6/1, B0-6/8, B0-6/11). It is
 * the only way to put anything into {@code audit_log.details}, and it is built from scalars only:
 * there is no way to hand it an object, a map or a domain entity to serialize.
 *
 * <p>Stored as
 *
 * <pre>{"v":1,"attributes":{"format":"CSV","rowCount":120},
 *  "changes":[{"field":"role","before":"EMPLOYEE","after":"ADMIN"},{"field":"email"}]}</pre>
 *
 * <p>Rules (approved B0-6 defaults; changing one is an explicit contract change):
 *
 * <ul>
 *   <li>keys and field names match {@code [a-z][A-Za-z0-9_]{0,39}};
 *   <li>string values are tokens, {@code [A-Za-z0-9._:-]{1,64}}: ids and codes, never free text (no
 *       spaces, no {@code @}, so an email or a name cannot be stored);
 *   <li>at most {@value #MAX_ATTRIBUTES} attributes and {@value #MAX_CHANGES} changes, no duplicate
 *       keys or fields, and at most {@value #MAX_BYTES} bytes serialized;
 *   <li>a key or field that names a secret (password, secret, token, OTP, recovery code,
 *       credential, API key) cannot carry a value. {@link Builder#changed(String)} records that
 *       such a field changed, without any value. The name check is a backstop for mistakes, not the
 *       control: the control is that only reviewed scalars fit through this API.
 * </ul>
 */
public final class AuditDetails {

    public static final int VERSION = 1;
    public static final int MAX_ATTRIBUTES = 20;
    public static final int MAX_CHANGES = 20;
    public static final int MAX_BYTES = 4096;

    static final Pattern KEY = Pattern.compile("[a-z][A-Za-z0-9_]{0,39}");
    static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    // Names that must never carry a value, whatever the casing or separators.
    private static final Pattern SENSITIVE_NAME =
            Pattern.compile(
                    "(?i).*(password|passwd|secret|token|otp|recovery|credential|apikey|api_key).*");

    private static final AuditDetails NONE = new AuditDetails(Map.of(), List.of());

    // Only used to measure the serialized size when details are built.
    private static final JsonMapper SIZE_CHECK = JsonMapper.builder().build();

    private final Map<String, Object> attributes;
    private final List<Change> changes;

    private AuditDetails(Map<String, Object> attributes, List<Change> changes) {
        this.attributes = attributes;
        this.changes = changes;
    }

    /** No metadata beyond the version marker. */
    public static AuditDetails none() {
        return NONE;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The JSON stored in {@code audit_log.details}. */
    String toJson(JsonMapper mapper) {
        ObjectNode root = mapper.createObjectNode();
        root.put("v", VERSION);
        if (!attributes.isEmpty()) {
            ObjectNode attrs = root.putObject("attributes");
            attributes.forEach(
                    (key, value) -> {
                        if (value instanceof String s) {
                            attrs.put(key, s);
                        } else if (value instanceof Long l) {
                            attrs.put(key, l.longValue());
                        } else {
                            attrs.put(key, ((Boolean) value).booleanValue());
                        }
                    });
        }
        if (!changes.isEmpty()) {
            ArrayNode array = root.putArray("changes");
            for (Change change : changes) {
                ObjectNode node = array.addObject();
                node.put("field", change.field());
                if (change.valueRecorded()) {
                    node.put("before", change.before());
                    node.put("after", change.after());
                }
            }
        }
        String json = mapper.writeValueAsString(root);
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException(
                    "Audit details exceed " + MAX_BYTES + " bytes when serialized");
        }
        return json;
    }

    private record Change(String field, boolean valueRecorded, String before, String after) {}

    public static final class Builder {

        private final Map<String, Object> attributes = new LinkedHashMap<>();
        private final List<Change> changes = new ArrayList<>();

        private Builder() {}

        public Builder attribute(String key, String value) {
            return putAttribute(key, requireToken(value, "attribute " + key));
        }

        public Builder attribute(String key, long value) {
            return putAttribute(key, value);
        }

        public Builder attribute(String key, boolean value) {
            return putAttribute(key, value);
        }

        /**
         * A field whose value changed, with the old and new value as tokens. {@code before} or
         * {@code after} may be {@code null} for "was unset" or "is now unset". A field that names a
         * secret is refused here: use {@link #changed(String)}.
         */
        public Builder change(String field, String before, String after) {
            requireKey(field, "change field");
            if (SENSITIVE_NAME.matcher(field).matches()) {
                throw new IllegalArgumentException(
                        "Field '" + field + "' may only be recorded with changed(field)");
            }
            requireNewField(field);
            changes.add(
                    new Change(
                            field,
                            true,
                            before == null ? null : requireToken(before, "before of " + field),
                            after == null ? null : requireToken(after, "after of " + field)));
            return this;
        }

        /** Records that a field changed without storing any value (secrets, personal data). */
        public Builder changed(String field) {
            requireKey(field, "changed field");
            requireNewField(field);
            changes.add(new Change(field, false, null, null));
            return this;
        }

        public AuditDetails build() {
            if (attributes.isEmpty() && changes.isEmpty()) {
                return NONE;
            }
            // Copies, so the builder can be reused without changing a built instance. The insertion
            // order is kept: it is the order of the stored JSON.
            AuditDetails details =
                    new AuditDetails(
                            Collections.unmodifiableMap(new LinkedHashMap<>(attributes)),
                            List.copyOf(changes));
            // Fail where the details are built, not later at the insert.
            details.toJson(SIZE_CHECK);
            return details;
        }

        private Builder putAttribute(String key, Object value) {
            requireKey(key, "attribute key");
            if (SENSITIVE_NAME.matcher(key).matches()) {
                throw new IllegalArgumentException(
                        "Attribute '" + key + "' names a secret and cannot carry a value");
            }
            if (attributes.containsKey(key)) {
                throw new IllegalArgumentException("Duplicate attribute '" + key + "'");
            }
            if (attributes.size() >= MAX_ATTRIBUTES) {
                throw new IllegalArgumentException("At most " + MAX_ATTRIBUTES + " attributes");
            }
            attributes.put(key, value);
            return this;
        }

        private void requireNewField(String field) {
            if (changes.size() >= MAX_CHANGES) {
                throw new IllegalArgumentException("At most " + MAX_CHANGES + " changes");
            }
            for (Change existing : changes) {
                if (existing.field().equals(field)) {
                    throw new IllegalArgumentException("Duplicate change '" + field + "'");
                }
            }
        }

        private static void requireKey(String key, String what) {
            if (key == null || !KEY.matcher(key).matches()) {
                throw new IllegalArgumentException(
                        "Audit " + what + " must match " + KEY.pattern());
            }
        }

        private static String requireToken(String value, String what) {
            if (value == null || !TOKEN.matcher(value).matches()) {
                // The value is not echoed: it is exactly what must not leak.
                throw new IllegalArgumentException(
                        "Audit " + what + " must be a token matching " + TOKEN.pattern());
            }
            return value;
        }
    }
}
