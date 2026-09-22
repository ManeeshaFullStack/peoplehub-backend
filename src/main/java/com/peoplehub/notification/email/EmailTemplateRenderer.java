package com.peoplehub.notification.email;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Renders an {@link EmailMessage}'s {@code type} + {@link EmailPayload} into a subject and a body
 * (b1-2), using classpath templates and minimal {@code {{key}}} token substitution -- no templating
 * engine dependency, per the b1-2 decision.
 *
 * <p>A template lives at {@code classpath:email-templates/<TYPE>.txt}: its first line is the
 * subject, its second line is blank, and every line after that is the body. Reads the payload
 * through {@link EmailPayload#toJson}, the same method the database insert uses, rather than adding
 * a new accessor to that class.
 *
 * <p>An unknown {@code type} or a token the payload does not supply both throw {@link
 * TemplateRenderException}, which {@link EmailOutboxProcessor} treats as a permanent failure with
 * no retry: neither will resolve itself by trying again.
 */
@Component
public class EmailTemplateRenderer {

    private static final Pattern TOKEN = Pattern.compile("\\{\\{([a-z][A-Za-z0-9_]{0,39})}}");

    private final JsonMapper json;

    public EmailTemplateRenderer(JsonMapper json) {
        this.json = json;
    }

    public record Rendered(String subject, String body) {}

    /**
     * @param payloadJson the payload's stored JSON shape ({@code {"v":1,"attributes":{...}}}), for
     *     example {@code email_outbox.payload} read back as text, or {@link
     *     EmailPayload#toJson(JsonMapper)} in a test
     */
    public Rendered render(String type, String payloadJson) {
        List<String> lines = loadTemplate(type).lines().toList();
        if (lines.size() < 3 || !lines.get(1).isBlank()) {
            throw new TemplateRenderException(
                    "Template for '"
                            + type
                            + "' must be a subject line, a blank line, then the body");
        }
        JsonNode attributes = json.readTree(payloadJson).path("attributes");
        String subject = substitute(lines.get(0), attributes, type);
        String body =
                substitute(String.join("\n", lines.subList(2, lines.size())), attributes, type);
        return new Rendered(subject, body);
    }

    private String loadTemplate(String type) {
        ClassPathResource resource = new ClassPathResource("email-templates/" + type + ".txt");
        if (!resource.exists()) {
            throw new TemplateRenderException("No email template for type '" + type + "'");
        }
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new TemplateRenderException(
                    "Could not read email template for type '" + type + "'");
        }
    }

    private String substitute(String text, JsonNode attributes, String type) {
        Matcher matcher = TOKEN.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            JsonNode value = attributes.path(key);
            if (value.isMissingNode()) {
                throw new TemplateRenderException(
                        "Template for '" + type + "' needs attribute '" + key + "'");
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value.asString()));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
