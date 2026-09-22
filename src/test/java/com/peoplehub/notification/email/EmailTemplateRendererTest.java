package com.peoplehub.notification.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Minimal {@code {{key}}} token substitution against a classpath template (b1-2). Uses the real
 * {@code EMPLOYEE_INVITED} template shipped under {@code src/main/resources/email-templates/}.
 */
class EmailTemplateRendererTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final EmailTemplateRenderer renderer = new EmailTemplateRenderer(JSON);

    private static String payloadJson(String... keyValuePairs) {
        EmailPayload.Builder builder = EmailPayload.builder();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            builder.attribute(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return builder.build().toJson(JSON);
    }

    @Test
    void substitutesEveryTokenFromThePayload() {
        String payload =
                payloadJson("appName", "PeopleHub", "firstName", "Jane", "inviteCode", "AB12CD");

        EmailTemplateRenderer.Rendered rendered = renderer.render("EMPLOYEE_INVITED", payload);

        assertThat(rendered.subject()).isEqualTo("Welcome to PeopleHub, Jane!");
        assertThat(rendered.body())
                .contains("Hi Jane,")
                .contains("You've been invited to join PeopleHub.")
                .contains("Your invite code is AB12CD.");
    }

    @Test
    void anUnknownTypeFailsWithNoRetryEligibleException() {
        assertThatThrownBy(() -> renderer.render("NO_SUCH_TEMPLATE", payloadJson()))
                .isInstanceOf(TemplateRenderException.class);
    }

    @Test
    void aMissingRequiredTokenFails() {
        // The template needs appName, firstName and inviteCode; this payload supplies only two.
        String payload = payloadJson("appName", "PeopleHub", "firstName", "Jane");

        assertThatThrownBy(() -> renderer.render("EMPLOYEE_INVITED", payload))
                .isInstanceOf(TemplateRenderException.class)
                .hasMessageContaining("inviteCode");
    }
}
