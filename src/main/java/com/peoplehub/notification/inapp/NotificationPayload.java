package com.peoplehub.notification.inapp;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The structured, deliberately supplied data of one in-app notification (Spec 9, 12; b1-3). It is
 * the only way to put anything into {@code notification.payload}, built from scalars only. Mirrors
 * {@code EmailPayload}'s contract (same limits, same token rule, same secret-name backstop)
 * deliberately without sharing code with it: modules talk through their own APIs, not by reaching
 * into each other's internals.
 *
 * <p>Stored as
 *
 * <pre>{"v":1,"attributes":{"requestId":"req-42"}}</pre>
 *
 * <p>Rules (same defaults as {@code EmailPayload}/{@code AuditDetails}, B0-6/11; changing one is an
 * explicit contract change):
 *
 * <ul>
 *   <li>keys match {@code [a-z][A-Za-z0-9_]{0,39}};
 *   <li>string values are tokens, {@code [A-Za-z0-9._:-]{1,64}}: ids and codes, never free text;
 *   <li>at most {@value #MAX_ATTRIBUTES} attributes, no duplicate keys, at most {@value #MAX_BYTES}
 *       bytes serialized;
 *   <li>a key that names a secret cannot carry a value.
 * </ul>
 */
public final class NotificationPayload {

    public static final int VERSION = 1;
    public static final int MAX_ATTRIBUTES = 20;
    public static final int MAX_BYTES = 4096;

    static final Pattern KEY = Pattern.compile("[a-z][A-Za-z0-9_]{0,39}");
    static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    private static final Pattern SENSITIVE_NAME =
            Pattern.compile(
                    "(?i).*(password|passwd|secret|token|otp|recovery|credential|apikey|api_key).*");

    private static final NotificationPayload NONE = new NotificationPayload(Map.of());

    private static final JsonMapper SIZE_CHECK = JsonMapper.builder().build();

    private final Map<String, Object> attributes;

    private NotificationPayload(Map<String, Object> attributes) {
        this.attributes = attributes;
    }

    public static NotificationPayload none() {
        return NONE;
    }

    public static Builder builder() {
        return new Builder();
    }

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
                    "Notification payload exceeds " + MAX_BYTES + " bytes when serialized");
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

        public NotificationPayload build() {
            if (attributes.isEmpty()) {
                return NONE;
            }
            NotificationPayload payload =
                    new NotificationPayload(
                            Collections.unmodifiableMap(new LinkedHashMap<>(attributes)));
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
                        "Notification payload attribute key must match " + KEY.pattern());
            }
        }

        private static String requireToken(String value, String what) {
            if (value == null || !TOKEN.matcher(value).matches()) {
                throw new IllegalArgumentException(
                        "Notification payload "
                                + what
                                + " must be a token matching "
                                + TOKEN.pattern());
            }
            return value;
        }
    }
}
