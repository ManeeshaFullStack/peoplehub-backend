package com.peoplehub.support;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Asserts that two answers tell a client nothing apart (b2-8 C5; Spec D22, 13.0, 15.1): the same
 * status, the same whole body and every same header and cookie, leaving out only what is unique to
 * each request by design (the correlation id, the problem {@code instance} derived from it).
 *
 * <p>Deterministic by construction: it compares what a client can read, never how long it took.
 * Timing is deliberately not compared here (see the C5 enumeration tests for why).
 */
public final class IndistinguishableResponses {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private IndistinguishableResponses() {}

    /** Fails unless {@code actual} is indistinguishable from {@code expected}. */
    public static void assertIndistinguishable(MvcResult expected, MvcResult actual, String what)
            throws Exception {
        assertThat(actual.getResponse().getStatus())
                .as(what + ": status")
                .isEqualTo(expected.getResponse().getStatus());
        assertThat(comparableBody(actual)).as(what + ": body").isEqualTo(comparableBody(expected));
        assertThat(comparableHeaders(actual))
                .as(what + ": headers")
                .isEqualTo(comparableHeaders(expected));
        assertThat(cookies(actual)).as(what + ": cookies").isEqualTo(cookies(expected));
    }

    /** The whole body, less the two per-request problem fields. */
    public static Object comparableBody(MvcResult result) throws Exception {
        String text = result.getResponse().getContentAsString();
        if (text.isEmpty()) {
            return "";
        }
        Object parsed = JSON.readValue(text, new TypeReference<Object>() {});
        if (parsed instanceof Map<?, ?> map) {
            Map<Object, Object> body = new HashMap<>(map);
            body.remove("instance");
            body.remove("correlationId");
            return body;
        }
        return parsed;
    }

    /** Every header, less the per-request correlation id. */
    public static Map<String, List<String>> comparableHeaders(MvcResult result) {
        Map<String, List<String>> headers = new TreeMap<>();
        for (String name : result.getResponse().getHeaderNames()) {
            if (!name.equalsIgnoreCase("X-Correlation-Id")) {
                headers.put(name, result.getResponse().getHeaders(name));
            }
        }
        return headers;
    }

    /** Each cookie set, as name, value, path and lifetime. */
    public static List<String> cookies(MvcResult result) {
        return Stream.of(result.getResponse().getCookies())
                .map(
                        (Cookie c) ->
                                c.getName()
                                        + "="
                                        + c.getValue()
                                        + ";path="
                                        + c.getPath()
                                        + ";max-age="
                                        + c.getMaxAge())
                .sorted()
                .toList();
    }
}
