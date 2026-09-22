package com.peoplehub.notification.email;

import com.peoplehub.common.api.error.ApiProblemException;
import com.peoplehub.common.api.error.ProblemType;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Inbound bounce/complaint webhook (b1-4, Spec 9.2): {@code POST /webhooks/email/events} (served
 * under {@code /api/v1} like every controller in this application, via {@code WebConfig}'s package
 * predicate). Unlike b1-1/b1-2/b1-3's endpoints, this is a <em>real</em>, always-active controller,
 * not a {@code @Profile}-guarded test-only one: its authentication is a shared-secret HMAC
 * signature ({@link WebhookSignatureVerifier}), not an employee/org principal, so it does not
 * depend on B2's auth model the way the deferred notification endpoints did.
 *
 * <p>The request body is a small, self-defined, provider-agnostic contract -- no real email
 * provider has been chosen yet, so this cannot claim to match any specific ESP's exact webhook
 * schema:
 *
 * <pre>{"email": "jane@example.com", "eventType": "BOUNCE"}</pre>
 *
 * {@code eventType} is {@code BOUNCE} or {@code COMPLAINT} only -- never {@code ADDRESS_REJECTED},
 * which is this application's own classification of a synchronous SMTP-time rejection ({@link
 * EmailOutboxProcessor}), not something an external provider reports.
 */
@RestController
@RequestMapping("/webhooks/email/events")
public class EmailWebhookController {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("[^\\s@]+@[^\\s@]+\\.[^\\s@]+");

    public record EmailEvent(String email, String eventType) {}

    private final WebhookSignatureVerifier verifier;
    private final EmailSuppressionService suppressionService;
    private final JsonMapper json;
    private final String webhookSecret;

    public EmailWebhookController(
            WebhookSignatureVerifier verifier,
            EmailSuppressionService suppressionService,
            JsonMapper json,
            @Value("${peoplehub.email.webhook-secret:#{null}}") String webhookSecret) {
        this.verifier = verifier;
        this.suppressionService = suppressionService;
        this.json = json;
        this.webhookSecret = webhookSecret;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void receive(
            @RequestHeader(value = "X-Webhook-Signature", required = false) String signature,
            @RequestBody byte[] rawBody) {
        if (!verifier.isValid(webhookSecret, rawBody, signature)) {
            // Deliberately generic: never confirms or denies *why* (missing secret configuration
            // vs. a genuinely wrong signature look identical to the caller), same non-enumerating
            // spirit as the rest of this codebase's auth-adjacent error messages.
            throw new ApiProblemException(ProblemType.UNAUTHORIZED, "Invalid webhook signature.");
        }
        EmailEvent event = parse(rawBody);
        suppressionService.suppress(event.email(), reasonFor(event.eventType()));
    }

    private EmailEvent parse(byte[] rawBody) {
        EmailEvent event;
        try {
            event = json.readValue(rawBody, EmailEvent.class);
        } catch (JacksonException e) {
            throw new ApiProblemException(
                    ProblemType.MALFORMED_REQUEST, "Request body is not valid JSON.");
        }
        if (event == null
                || event.email() == null
                || !EMAIL_PATTERN.matcher(event.email()).matches()) {
            throw new ApiProblemException(
                    ProblemType.MALFORMED_REQUEST,
                    "'email' is required and must look like an email address.");
        }
        return event;
    }

    private SuppressionReason reasonFor(String eventType) {
        if ("BOUNCE".equals(eventType)) {
            return SuppressionReason.BOUNCE;
        }
        if ("COMPLAINT".equals(eventType)) {
            return SuppressionReason.COMPLAINT;
        }
        throw new ApiProblemException(
                ProblemType.MALFORMED_REQUEST, "'eventType' must be 'BOUNCE' or 'COMPLAINT'.");
    }
}
