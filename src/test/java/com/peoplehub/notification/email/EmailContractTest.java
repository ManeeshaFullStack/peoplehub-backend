package com.peoplehub.notification.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The outbox writer's contract without a database (b1-1): what template data can be supplied, what
 * a message requires, and that the writer's API cannot mutate or serialize arbitrary objects.
 * Mirrors {@code AuditContractTest}'s coverage of the equivalent b0-6 contract. The database
 * enforces the same rules again ({@code EmailOutboxMigrationTest}).
 */
class EmailContractTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID ORG = UUID.fromString("6f1c5a0e-3b7d-4f7e-9a53-1d2c3b4a5e6f");

    private static JsonNode json(EmailPayload payload) {
        return JSON.readTree(payload.toJson(JSON));
    }

    // ---- EmailPayload: shape ----

    @Test
    void noPayloadIsJustTheVersionMarker() {
        assertThat(json(EmailPayload.none())).isEqualTo(JSON.readTree("{\"v\":1}"));
        assertThat(EmailPayload.builder().build()).isSameAs(EmailPayload.none());
    }

    @Test
    void payloadIsStoredInTheVersionedStructure() {
        EmailPayload payload =
                EmailPayload.builder()
                        .attribute("firstName", "Jane")
                        .attribute("inviteCode", "AB12CD")
                        .attribute("rowCount", 120L)
                        .attribute("dryRun", false)
                        .build();

        assertThat(json(payload))
                .isEqualTo(
                        JSON.readTree(
                                """
                                {"v":1,
                                 "attributes":{"firstName":"Jane","inviteCode":"AB12CD",
                                               "rowCount":120,"dryRun":false}}
                                """));
    }

    @Test
    void aBuiltPayloadIsNotAffectedByLaterUseOfTheBuilder() {
        EmailPayload.Builder builder = EmailPayload.builder().attribute("a", "x");
        EmailPayload first = builder.build();
        builder.attribute("b", "y");

        assertThat(json(first).path("attributes").propertyNames()).containsExactly("a");
    }

    // ---- EmailPayload: values are tokens, never free text or personal data ----

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "two words",
                "person@example.com",
                "line\nbreak",
                "tab\there",
                "trailing-newline\n",
                "semi;colon",
                "quote'quote",
                "brace{}",
                "ünïcode",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" // 65
            })
    void stringValuesMustBeTokens(String value) {
        assertThatThrownBy(() -> EmailPayload.builder().attribute("note", value))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aTokenOfExactlyTheMaximumLengthIsAccepted() {
        String max = "a".repeat(64);

        assertThatCode(() -> EmailPayload.builder().attribute("note", max).build())
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> EmailPayload.builder().attribute("note", max + "a"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRejectedValueIsNeverEchoedInTheError() {
        String secret = "person@example.com";

        assertThatThrownBy(() -> EmailPayload.builder().attribute("who", secret))
                .isInstanceOf(IllegalArgumentException.class)
                .message()
                .doesNotContain(secret);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Upper", "1digit", "has-dash", "has space", "under score\n"})
    void keysMustBeLowerCamelNames(String key) {
        assertThatThrownBy(() -> EmailPayload.builder().attribute(key, "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullKeysAndValuesAreRejected() {
        assertThatThrownBy(() -> EmailPayload.builder().attribute(null, "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EmailPayload.builder().attribute("key", (String) null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "password",
                "newPassword",
                "passwordHash",
                "secret",
                "clientSecret",
                "accessToken",
                "refresh_token",
                "otp",
                "recoveryCode",
                "credential",
                "apiKey",
                "api_key"
            })
    void aSecretsNameCannotCarryAValue(String name) {
        assertThatThrownBy(() -> EmailPayload.builder().attribute(name, "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EmailPayload.builder().attribute(name, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EmailPayload.builder().attribute(name, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- EmailPayload: limits ----

    @Test
    void atMostTwentyAttributes() {
        EmailPayload.Builder builder = EmailPayload.builder();
        for (int i = 0; i < EmailPayload.MAX_ATTRIBUTES; i++) {
            builder.attribute("k" + i, "v");
        }

        assertThatThrownBy(() -> builder.attribute("oneMore", "v"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void duplicateKeysAreRejected() {
        assertThatThrownBy(() -> EmailPayload.builder().attribute("a", "x").attribute("a", "y"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theLargestPossiblePayloadStaysWellUnderTheByteLimit() {
        // Unlike AuditDetails (which can also carry up to 20 "changes", each contributing a field
        // plus a before/after pair), EmailPayload has attributes only. Twenty attributes at the
        // longest allowed key (40 chars) and value (64 chars) serialize to roughly 2.2KB, so
        // MAX_BYTES (4096, the same figure as AuditDetails' cap by deliberate reuse) cannot
        // actually
        // be reached through this builder today. The check stays as defence in depth for if that
        // ever changes (for example if b1-2 needs longer template values), rather than a path this
        // test can exercise now.
        String maxKeyPrefix =
                "a".repeat(38); // + 2-digit suffix = 40 chars, the KEY pattern's limit
        String maxValue = "t".repeat(64); // the TOKEN pattern's limit
        EmailPayload.Builder builder = EmailPayload.builder();
        for (int i = 0; i < EmailPayload.MAX_ATTRIBUTES; i++) {
            builder.attribute(maxKeyPrefix + String.format("%02d", i), maxValue);
        }

        EmailPayload payload = builder.build();

        assertThat(payload.toJson(JSON).getBytes(StandardCharsets.UTF_8).length)
                .isLessThan(EmailPayload.MAX_BYTES);
    }

    // ---- EmailMessage ----

    @Test
    void aMessageNeedsARealOrganizationId() {
        assertThatThrownBy(
                        () ->
                                EmailMessage.builder(null, "jane@example.com", "SOMETHING_HAPPENED")
                                        .build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                EmailMessage.builder(
                                                new UUID(0L, 0L),
                                                "jane@example.com",
                                                "SOMETHING_HAPPENED")
                                        .build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(
                        () ->
                                EmailMessage.builder(ORG, "jane@example.com", "SOMETHING_HAPPENED")
                                        .build())
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "lower_case",
                "Mixed_Case",
                "1_STARTS_WITH_DIGIT",
                "HAS-DASH",
                "HAS SPACE",
                "TRAILING_NEWLINE\n",
                "A23456789012345678901234567890123456789012345678901234567890123456" // 66
            })
    void typesMustBeUpperSnakeCase(String value) {
        assertThatThrownBy(() -> EmailMessage.builder(ORG, "jane@example.com", value).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void typesOfExactlySixtyFourCharactersAreAccepted() {
        String max = "A" + "B".repeat(63);

        assertThatCode(() -> EmailMessage.builder(ORG, "jane@example.com", max).build())
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> EmailMessage.builder(ORG, "jane@example.com", max + "B").build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "not-an-address",
                "missing-at.example.com",
                "two@@example.com",
                "no-domain@"
            })
    void recipientMustLookLikeAnEmailAddress(String recipient) {
        assertThatThrownBy(() -> EmailMessage.builder(ORG, recipient, "SOMETHING_HAPPENED").build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aMissingPayloadBecomesNoPayload() {
        EmailMessage message =
                EmailMessage.builder(ORG, "jane@example.com", "SOMETHING_HAPPENED")
                        .payload(null)
                        .build();

        assertThat(message.payload()).isSameAs(EmailPayload.none());
    }

    // ---- the API cannot mutate, and cannot take arbitrary objects ----

    @Test
    void theWriterCanOnlyEnqueue() {
        List<String> publicMethods =
                Arrays.stream(EmailOutboxWriter.class.getDeclaredMethods())
                        .filter(m -> Modifier.isPublic(m.getModifiers()))
                        .map(Method::getName)
                        .toList();

        assertThat(publicMethods).containsExactly("enqueue");
        assertThat(EmailOutboxWriter.class.getSuperclass()).isEqualTo(Object.class);
        assertThat(EmailOutboxWriter.class.getInterfaces()).isEmpty();
    }

    @Test
    void noMutationVocabularyAnywhereInThePublicSurface() {
        Set<String> forbidden =
                Set.of(
                        "update",
                        "delete",
                        "remove",
                        "truncate",
                        "save",
                        "merge",
                        "persist",
                        "replace",
                        "clear",
                        "purge",
                        "send");

        for (Class<?> type :
                List.of(
                        EmailOutboxWriter.class,
                        EmailMessage.class,
                        EmailMessage.Builder.class,
                        EmailPayload.class,
                        EmailPayload.Builder.class)) {
            for (Method method : type.getMethods()) {
                String name = method.getName().toLowerCase();
                assertThat(forbidden.stream().filter(name::contains))
                        .as(type.getSimpleName() + "." + method.getName())
                        .isEmpty();
            }
        }
    }

    @Test
    void thePublicApiOnlyTakesReviewedScalarsAndEmailTypes() {
        Set<Class<?>> allowed =
                Set.of(
                        String.class,
                        long.class,
                        boolean.class,
                        UUID.class,
                        EmailPayload.class,
                        EmailMessage.class);

        for (Class<?> type :
                List.of(
                        EmailOutboxWriter.class,
                        EmailMessage.class,
                        EmailMessage.Builder.class,
                        EmailPayload.class,
                        EmailPayload.Builder.class)) {
            for (Method method : type.getDeclaredMethods()) {
                if (!Modifier.isPublic(method.getModifiers())
                        || method.getName().equals("equals")) {
                    continue;
                }
                for (Class<?> parameter : method.getParameterTypes()) {
                    assertThat(allowed)
                            .as(type.getSimpleName() + "." + method.getName())
                            .contains(parameter);
                    assertThat(Map.class.isAssignableFrom(parameter)).isFalse();
                    assertThat(Collection.class.isAssignableFrom(parameter)).isFalse();
                }
            }
        }
        for (var component : EmailMessage.class.getRecordComponents()) {
            assertThat(allowed).as(component.getName()).contains(component.getType());
        }
    }
}
