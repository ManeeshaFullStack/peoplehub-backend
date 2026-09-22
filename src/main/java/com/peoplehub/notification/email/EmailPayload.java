package com.peoplehub.notification.email;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The structured, deliberately supplied template data of one outbox row (Spec 9.2, 12; b1-1). It is
 * the only way to put anything into {@code email_outbox.payload}, and it is built from scalars
 * only: there is no way to hand it an object, a map or a domain entity to serialize. Deliberately
 * mirrors {@code AuditDetails}' contract (same limits, same token rule, same secret-name backstop)
 * rather than sharing code with it: modules talk through their own APIs, not by reaching into each
 * other's internals.
 *
 * <p>Stored as
 *
 * <pre>{"v":1,"attributes":{"firstName":"Jane","inviteCode":"AB12CD"}}</pre>
 *
 * <p>Rules (same defaults as {@code AuditDetails}, B0-6/11; changing one is an explicit contract
 * change):
 *
 * <ul>
 *   <li>keys match {@code [a-z][A-Za-z0-9_]{0,39}};
 *   <li>string values are tokens, {@code [A-Za-z0-9._:-]{1,64}}: ids and codes, never free text (no
 *       spaces, no {@code @}, so an email or a name cannot be stored);
 *   <li>at most {@value #MAX_ATTRIBUTES} attributes, no duplicate keys, and at most {@value
 *       #MAX_BYTES} bytes serialized;
 *   <li>a key that names a secret (password, secret, token, OTP, recovery code, credential, API
 *       key) cannot carry a value. The name check is a backstop for mistakes, not the control: the
 *       control is that only reviewed scalars fit through this API.
 * </ul>
 */
public final class EmailPayload {

    public static final int VERSION = 1;
    public static final int MAX_ATTRIBUTES = 20;
    public static final int MAX_BYTES = 4096;

    static final Pattern KEY = Pattern.compile("[a-z][A-Za-z0-9_]{0,39}");
    static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    // Names that must never carry a value, whatever the casing or separators.
    private static final Pattern SENSITIVE_NAME =
            Pattern.compile(
                    "(?i).*(password|passwd|secret|token|otp|recovery|credential|apikey|api_key).*");

    private static final EmailPayload NONE = new EmailPayload(Map.of());

    // Only used to measure the serialized size when a payload is built.
    private static final JsonMapper SIZE_CHECK = JsonMapper.builder().build();

    private final Map<String, Object> attributes;

    private EmailPayload(Map<String, Object> attributes) {
        this.attributes = attributes;
    }

    /** No template data beyond the version marker. */
    public static EmailPayload none() {
        return NONE;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The JSON stored in {@code email_outbox.payload}. */
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
        String json = mapper.writeValueAsString(root);
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException(
                    "Email payload exceeds " + MAX_BYTES + " bytes when serialized");
        }
        return json;
    }

    public static final class Builder {

        private final Map<String, Object> attributes = new LinkedHashMap<>();

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

        public EmailPayload build() {
            if (attributes.isEmpty()) {
                return NONE;
            }
            // A copy, so the builder can be reused without changing a built instance. The insertion
            // order is kept: it is the order of the stored JSON.
            EmailPayload payload =
                    new EmailPayload(Collections.unmodifiableMap(new LinkedHashMap<>(attributes)));
            // Fail where the payload is built, not later at the insert.
            payload.toJson(SIZE_CHECK);
            return payload;
        }

        private Builder putAttribute(String key, Object value) {
            requireKey(key);
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

        private static void requireKey(String key) {
            if (key == null || !KEY.matcher(key).matches()) {
                throw new IllegalArgumentException(
                        "Email payload attribute key must match " + KEY.pattern());
            }
        }

        private static String requireToken(String value, String what) {
            if (value == null || !TOKEN.matcher(value).matches()) {
                // The value is not echoed: it is exactly what must not leak.
                throw new IllegalArgumentException(
                        "Email payload " + what + " must be a token matching " + TOKEN.pattern());
            }
            return value;
        }
    }
}
