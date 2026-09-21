package com.peoplehub.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.InetAddress;
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
 * The audit writer's contract without a database (B0-6/1, B0-6/8, B0-6/9, B0-6/11): what metadata
 * can be supplied, what an event requires, and that the writer's API cannot mutate or serialize
 * arbitrary objects. The database enforces the same rules again ({@code AuditLogMigrationTest}).
 */
class AuditContractTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID ORG = UUID.fromString("6f1c5a0e-3b7d-4f7e-9a53-1d2c3b4a5e6f");

    private static JsonNode json(AuditDetails details) {
        return JSON.readTree(details.toJson(JSON));
    }

    // ---- AuditDetails: shape ----

    @Test
    void noDetailsIsJustTheVersionMarker() {
        assertThat(json(AuditDetails.none())).isEqualTo(JSON.readTree("{\"v\":1}"));
        assertThat(AuditDetails.builder().build()).isSameAs(AuditDetails.none());
    }

    @Test
    void detailsAreStoredInTheVersionedStructure() {
        AuditDetails details =
                AuditDetails.builder()
                        .attribute("format", "CSV")
                        .attribute("rowCount", 120L)
                        .attribute("dryRun", false)
                        .change("role", "EMPLOYEE", "ADMIN")
                        .change("managerId", null, "e-42")
                        .changed("email")
                        .build();

        assertThat(json(details))
                .isEqualTo(
                        JSON.readTree(
                                """
                                {"v":1,
                                 "attributes":{"format":"CSV","rowCount":120,"dryRun":false},
                                 "changes":[
                                   {"field":"role","before":"EMPLOYEE","after":"ADMIN"},
                                   {"field":"managerId","before":null,"after":"e-42"},
                                   {"field":"email"}]}
                                """));
    }

    @Test
    void aSensitiveFieldIsRecordedAsChangedWithoutAnyValue() {
        String stored = AuditDetails.builder().changed("password").build().toJson(JSON);

        assertThat(json(AuditDetails.builder().changed("password").build()).path("changes").get(0))
                .isEqualTo(JSON.readTree("{\"field\":\"password\"}"));
        assertThat(stored).doesNotContain("before").doesNotContain("after");
    }

    @Test
    void aBuiltDetailsIsNotAffectedByLaterUseOfTheBuilder() {
        AuditDetails.Builder builder = AuditDetails.builder().attribute("a", "x");
        AuditDetails first = builder.build();
        builder.attribute("b", "y");

        assertThat(json(first).path("attributes").propertyNames()).containsExactly("a");
    }

    // ---- AuditDetails: values are tokens, never free text or personal data ----

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
        assertThatThrownBy(() -> AuditDetails.builder().attribute("note", value))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditDetails.builder().change("note", value, "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditDetails.builder().change("note", "x", value))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aTokenOfExactlyTheMaximumLengthIsAccepted() {
        String max = "a".repeat(64);

        assertThatCode(() -> AuditDetails.builder().attribute("note", max).build())
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> AuditDetails.builder().attribute("note", max + "a"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRejectedValueIsNeverEchoedInTheError() {
        String secret = "person@example.com";

        assertThatThrownBy(() -> AuditDetails.builder().attribute("who", secret))
                .isInstanceOf(IllegalArgumentException.class)
                .message()
                .doesNotContain(secret);
        assertThatThrownBy(() -> AuditDetails.builder().change("who", secret, "x"))
                .message()
                .doesNotContain(secret);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Upper", "1digit", "has-dash", "has space", "under score\n"})
    void keysAndFieldsMustBeLowerCamelNames(String key) {
        assertThatThrownBy(() -> AuditDetails.builder().attribute(key, "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditDetails.builder().change(key, "x", "y"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditDetails.builder().changed(key))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullKeysAndValuesAreRejected() {
        assertThatThrownBy(() -> AuditDetails.builder().attribute(null, "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditDetails.builder().attribute("key", (String) null))
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
        assertThatThrownBy(() -> AuditDetails.builder().attribute(name, "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditDetails.builder().attribute(name, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditDetails.builder().attribute(name, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditDetails.builder().change(name, "a", "b"))
                .isInstanceOf(IllegalArgumentException.class);
        // The name alone, without a value, is the approved way to record it.
        assertThatCode(() -> AuditDetails.builder().changed(name).build())
                .doesNotThrowAnyException();
    }

    // ---- AuditDetails: limits ----

    @Test
    void atMostTwentyAttributes() {
        AuditDetails.Builder builder = AuditDetails.builder();
        for (int i = 0; i < AuditDetails.MAX_ATTRIBUTES; i++) {
            builder.attribute("k" + i, "v");
        }

        assertThatThrownBy(() -> builder.attribute("one" + "more", "v"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void atMostTwentyChanges() {
        AuditDetails.Builder builder = AuditDetails.builder();
        for (int i = 0; i < AuditDetails.MAX_CHANGES; i++) {
            builder.change("f" + i, "a", "b");
        }

        assertThatThrownBy(() -> builder.change("oneMore", "a", "b"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.changed("oneMore"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void duplicateKeysAndFieldsAreRejected() {
        assertThatThrownBy(() -> AuditDetails.builder().attribute("a", "x").attribute("a", "y"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditDetails.builder().change("f", "a", "b").change("f", "c", "d"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditDetails.builder().change("f", "a", "b").changed("f"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void detailsOverFourKilobytesSerializedAreRejectedWhenBuilt() {
        String token = "t".repeat(64);
        AuditDetails.Builder builder = AuditDetails.builder();
        // Twenty attributes and twenty changes, each as long as allowed: well over 4096 bytes.
        for (int i = 0; i < AuditDetails.MAX_ATTRIBUTES; i++) {
            builder.attribute("a".repeat(30) + String.format("%02d", i), token);
        }
        for (int i = 0; i < AuditDetails.MAX_CHANGES; i++) {
            builder.change("c".repeat(30) + String.format("%02d", i), token, token);
        }

        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("4096");
    }

    // ---- AuditEvent and AuditTarget ----

    @Test
    void anEventNeedsARealOrganizationId() {
        assertThatThrownBy(() -> AuditEvent.builder(null, "SOMETHING_HAPPENED").build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditEvent.builder(new UUID(0L, 0L), "SOMETHING_HAPPENED").build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> AuditEvent.builder(ORG, "SOMETHING_HAPPENED").build())
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
    void actionsAndTargetTypesMustBeUpperSnakeCase(String value) {
        assertThatThrownBy(() -> AuditEvent.builder(ORG, value).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditTarget.ofType(value))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void actionsAndTargetTypesOfExactlySixtyFourCharactersAreAccepted() {
        String max = "A" + "B".repeat(63);

        assertThatCode(() -> AuditEvent.builder(ORG, max).build()).doesNotThrowAnyException();
        assertThatCode(() -> AuditTarget.ofType(max)).doesNotThrowAnyException();
        assertThatThrownBy(() -> AuditEvent.builder(ORG, max + "B").build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "person@example.com",
                "Jane Doe",
                "id\n",
                "a/b",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" // 65
            })
    void aTargetIdIsAnIdentifierNotANameOrEmail(String id) {
        assertThatThrownBy(() -> AuditTarget.of("EMPLOYEE", id))
                .isInstanceOf(IllegalArgumentException.class)
                .message()
                .doesNotContain("Jane");
    }

    @Test
    void aTargetWithAnIdentifierIsAccepted() {
        assertThat(AuditTarget.of("EMPLOYEE", UUID.randomUUID().toString()).type())
                .isEqualTo("EMPLOYEE");
        assertThat(AuditTarget.ofType("EMPLOYEE_LIST").id()).isNull();
        assertThatThrownBy(() -> AuditTarget.of("EMPLOYEE", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aMissingDetailsBecomesNoDetails() throws Exception {
        AuditEvent event =
                AuditEvent.builder(ORG, "SOMETHING_HAPPENED")
                        .details(null)
                        .ip(InetAddress.getByName("203.0.113.7"))
                        .target(AuditTarget.of("EMPLOYEE", "e-1"))
                        .build();

        assertThat(event.details()).isSameAs(AuditDetails.none());
    }

    // ---- the API cannot mutate, and cannot take arbitrary objects ----

    @Test
    void theWriterCanOnlyAppend() {
        List<String> publicMethods =
                Arrays.stream(AuditWriter.class.getDeclaredMethods())
                        .filter(m -> Modifier.isPublic(m.getModifiers()))
                        .map(Method::getName)
                        .toList();

        assertThat(publicMethods).containsExactly("append");
        // Nothing inherited that could mutate either: it extends Object and implements nothing.
        assertThat(AuditWriter.class.getSuperclass()).isEqualTo(Object.class);
        assertThat(AuditWriter.class.getInterfaces()).isEmpty();
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
                        "purge");

        for (Class<?> type :
                List.of(
                        AuditWriter.class,
                        AuditEvent.class,
                        AuditEvent.Builder.class,
                        AuditTarget.class,
                        AuditDetails.class,
                        AuditDetails.Builder.class)) {
            for (Method method : type.getMethods()) {
                String name = method.getName().toLowerCase();
                assertThat(forbidden.stream().filter(name::contains))
                        .as(type.getSimpleName() + "." + method.getName())
                        .isEmpty();
            }
        }
    }

    @Test
    void thePublicApiOnlyTakesReviewedScalarsAndAuditTypes() {
        Set<Class<?>> allowed =
                Set.of(
                        String.class,
                        long.class,
                        boolean.class,
                        UUID.class,
                        InetAddress.class,
                        AuditTarget.class,
                        AuditDetails.class,
                        AuditEvent.class);

        for (Class<?> type :
                List.of(
                        AuditWriter.class,
                        AuditEvent.class,
                        AuditEvent.Builder.class,
                        AuditTarget.class,
                        AuditDetails.class,
                        AuditDetails.Builder.class)) {
            for (Method method : type.getDeclaredMethods()) {
                // equals(Object) is generated on records and carries no data anywhere.
                if (!Modifier.isPublic(method.getModifiers())
                        || method.getName().equals("equals")) {
                    continue;
                }
                for (Class<?> parameter : method.getParameterTypes()) {
                    // The allowed set holds no Object, Map, Collection or entity type, so anything
                    // else, including a generic "value" parameter, fails here.
                    assertThat(allowed)
                            .as(type.getSimpleName() + "." + method.getName())
                            .contains(parameter);
                    assertThat(Map.class.isAssignableFrom(parameter)).isFalse();
                    assertThat(Collection.class.isAssignableFrom(parameter)).isFalse();
                }
            }
        }
        for (var component : AuditEvent.class.getRecordComponents()) {
            assertThat(allowed).as(component.getName()).contains(component.getType());
        }
    }
}
