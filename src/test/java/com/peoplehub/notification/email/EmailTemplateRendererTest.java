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
                payloadJson(
                        "appName",
                        "PeopleHub",
                        "firstName",
                        "Jane",
                        "inviteCode",
                        "AB12CD",
                        "organizationLoginKey",
                        "acme-corp",
                        "role",
                        "EMPLOYEE");

        EmailTemplateRenderer.Rendered rendered = renderer.render("EMPLOYEE_INVITED", payload);

        // b2-4 (B2-4/O10): the invitation email names the role and the organization login key.
        assertThat(rendered.subject()).isEqualTo("You're invited to PeopleHub, Jane!");
        assertThat(rendered.body())
                .contains("Hi Jane,")
                .contains("You've been invited to join PeopleHub with the role EMPLOYEE.")
                .contains("Your invite code is AB12CD.")
                .contains("sign in with the organization acme-corp")
                .doesNotContain("{{");
    }

    @Test
    void thePasswordResetTemplateCarriesTheCodeAndItsExpiry() {
        String payload =
                EmailPayload.builder()
                        .attribute("appName", "PeopleHub")
                        .attribute("firstName", "Jane")
                        .attribute("organizationLoginKey", "acme-corp")
                        .attribute("resetCode", "AB12CD")
                        .attribute("expiryMinutes", 30)
                        .build()
                        .toJson(JSON);

        EmailTemplateRenderer.Rendered rendered = renderer.render("PASSWORD_RESET", payload);

        // b2-5 (B2-5/P6): the reset email carries the code only, no link yet.
        assertThat(rendered.subject()).isEqualTo("Reset your PeopleHub password");
        assertThat(rendered.body())
                .contains("Hi Jane,")
                .contains("in the organization acme-corp")
                .contains(
                        "Your reset code is AB12CD. It can be used once and expires in 30 minutes.")
                .doesNotContain("{{");
    }

    @Test
    void anUnknownTypeFailsWithNoRetryEligibleException() {
        assertThatThrownBy(() -> renderer.render("NO_SUCH_TEMPLATE", payloadJson()))
                .isInstanceOf(TemplateRenderException.class);
    }

    @Test
    void aMissingRequiredTokenFails() {
        // The template needs inviteCode (among others); this payload leaves it out.
        String payload =
                payloadJson(
                        "appName",
                        "PeopleHub",
                        "firstName",
                        "Jane",
                        "organizationLoginKey",
                        "acme-corp",
                        "role",
                        "EMPLOYEE");

        assertThatThrownBy(() -> renderer.render("EMPLOYEE_INVITED", payload))
                .isInstanceOf(TemplateRenderException.class)
                .hasMessageContaining("inviteCode");
    }
}
