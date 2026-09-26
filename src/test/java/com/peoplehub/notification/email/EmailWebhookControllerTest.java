package com.peoplehub.notification.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.peoplehub.support.IntegrationTest;
import com.peoplehub.support.PrivilegedFixture;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code POST /api/v1/webhooks/email/events} end to end (b1-4): a real, always-active controller
 * (unlike b1-1/b1-2/b1-3's deferred/test-only endpoints), so tested with a real HTTP request
 * through {@link MockMvc} against the full application context, not a slice.
 *
 * <p>Sets a fixed webhook secret via {@code @TestPropertySource} (the default resolves to {@code
 * null}, which would make every request unauthorized) -- its own context, {@code @DirtiesContext}
 * is not needed since nothing else uses this property.
 */
@IntegrationTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "peoplehub.email.webhook-secret=test-webhook-secret")
class EmailWebhookControllerTest {

    private static final String SECRET = "test-webhook-secret";
    private static final String PATH = "/api/v1/webhooks/email/events";

    @Autowired private MockMvc mvc;
    @Autowired @PrivilegedFixture private JdbcTemplate jdbc;
    @Autowired private EmailSuppressionService suppressionService;

    private static String sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void aCorrectlySignedBounceSuppressesTheAddress() throws Exception {
        String email = "bounce-" + UUID.randomUUID() + "@example.com";
        String body = "{\"email\":\"" + email + "\",\"eventType\":\"BOUNCE\"}";

        mvc.perform(
                        post(PATH)
                                .header("X-Webhook-Signature", sign(body))
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isNoContent());

        assertThat(suppressionService.isSuppressed(email)).isTrue();
        String reason =
                jdbc.queryForObject(
                        "SELECT reason FROM email_suppression WHERE email = ?",
                        String.class,
                        email);
        assertThat(reason).isEqualTo("BOUNCE");
    }

    @Test
    void aCorrectlySignedComplaintSuppressesTheAddress() throws Exception {
        String email = "complaint-" + UUID.randomUUID() + "@example.com";
        String body = "{\"email\":\"" + email + "\",\"eventType\":\"COMPLAINT\"}";

        mvc.perform(
                        post(PATH)
                                .header("X-Webhook-Signature", sign(body))
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isNoContent());

        assertThat(suppressionService.isSuppressed(email)).isTrue();
    }

    @Test
    void aMissingSignatureIsUnauthorizedAndNothingIsSuppressed() throws Exception {
        String email = "no-sig-" + UUID.randomUUID() + "@example.com";
        String body = "{\"email\":\"" + email + "\",\"eventType\":\"BOUNCE\"}";

        mvc.perform(post(PATH).contentType("application/json").content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("urn:peoplehub:problem:unauthorized"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());

        assertThat(suppressionService.isSuppressed(email)).isFalse();
    }

    @Test
    void aWrongSignatureIsUnauthorized() throws Exception {
        String email = "wrong-sig-" + UUID.randomUUID() + "@example.com";
        String body = "{\"email\":\"" + email + "\",\"eventType\":\"BOUNCE\"}";

        mvc.perform(
                        post(PATH)
                                .header(
                                        "X-Webhook-Signature",
                                        "0000000000000000000000000000000000000000000000000000000000000000")
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isUnauthorized());

        assertThat(suppressionService.isSuppressed(email)).isFalse();
    }

    @Test
    void aTamperedBodyAfterSigningIsUnauthorized() throws Exception {
        String signedBody = "{\"email\":\"original@example.com\",\"eventType\":\"BOUNCE\"}";
        String signature = sign(signedBody);
        String tamperedBody = "{\"email\":\"attacker@example.com\",\"eventType\":\"BOUNCE\"}";

        mvc.perform(
                        post(PATH)
                                .header("X-Webhook-Signature", signature)
                                .contentType("application/json")
                                .content(tamperedBody))
                .andExpect(status().isUnauthorized());

        assertThat(suppressionService.isSuppressed("attacker@example.com")).isFalse();
    }

    @Test
    void malformedJsonIsRejectedAsBadRequestNotAServerError() throws Exception {
        String body = "not json";

        mvc.perform(
                        post(PATH)
                                .header("X-Webhook-Signature", sign(body))
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:peoplehub:problem:malformed-request"));
    }

    @Test
    void anUnknownEventTypeIsRejectedAsBadRequest() throws Exception {
        String email = "unknown-type-" + UUID.randomUUID() + "@example.com";
        String body = "{\"email\":\"" + email + "\",\"eventType\":\"UNSUBSCRIBE\"}";

        mvc.perform(
                        post(PATH)
                                .header("X-Webhook-Signature", sign(body))
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isBadRequest());

        assertThat(suppressionService.isSuppressed(email)).isFalse();
    }

    @Test
    void addressRejectedIsNotAnAcceptedWebhookEventTypeEvenWhenProperlySigned() throws Exception {
        // ADDRESS_REJECTED is this application's own synchronous SMTP-time classification
        // (EmailFailureClassifier / EmailOutboxProcessor), never something the webhook accepts.
        String email = "internal-reason-" + UUID.randomUUID() + "@example.com";
        String body = "{\"email\":\"" + email + "\",\"eventType\":\"ADDRESS_REJECTED\"}";

        mvc.perform(
                        post(PATH)
                                .header("X-Webhook-Signature", sign(body))
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anInvalidEmailIsRejectedAsBadRequest() throws Exception {
        String body = "{\"email\":\"not-an-email\",\"eventType\":\"BOUNCE\"}";

        mvc.perform(
                        post(PATH)
                                .header("X-Webhook-Signature", sign(body))
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void respondingBodyCarriesNoStackTraceOrRawExceptionText() throws Exception {
        String body = "not json";

        String response =
                mvc.perform(
                                post(PATH)
                                        .header("X-Webhook-Signature", sign(body))
                                        .contentType("application/json")
                                        .content(body))
                        .andExpect(status().isBadRequest())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(response).doesNotContain("Exception").doesNotContain("tools.jackson");
    }
}
