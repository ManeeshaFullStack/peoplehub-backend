package com.peoplehub.notification.inapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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
 * The notification writer's contract without a database (b1-3): what data can be supplied, what a
 * message requires, and that the writer's API cannot mutate or serialize arbitrary objects. Mirrors
 * {@code EmailContractTest}'s coverage of the equivalent b1-1 contract. The database enforces the
 * same rules again ({@code NotificationMigrationTest}).
 */
class NotificationContractTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID ORG = UUID.fromString("6f1c5a0e-3b7d-4f7e-9a53-1d2c3b4a5e6f");
    private static final UUID EMPLOYEE = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static JsonNode json(NotificationPayload payload) {
        return JSON.readTree(payload.toJson(JSON));
    }

    // ---- NotificationPayload: shape ----

    @Test
    void noPayloadIsJustTheVersionMarker() {
        assertThat(json(NotificationPayload.none())).isEqualTo(JSON.readTree("{\"v\":1}"));
        assertThat(NotificationPayload.builder().build()).isSameAs(NotificationPayload.none());
    }

    @Test
    void payloadIsStoredInTheVersionedStructure() {
        NotificationPayload payload =
                NotificationPayload.builder().attribute("requestId", "req-42").build();

        assertThat(json(payload))
                .isEqualTo(JSON.readTree("{\"v\":1,\"attributes\":{\"requestId\":\"req-42\"}}"));
    }

    // ---- NotificationPayload: values are tokens ----

    @ParameterizedTest
    @ValueSource(strings = {"", "two words", "person@example.com", "line\nbreak"})
    void stringValuesMustBeTokens(String value) {
        assertThatThrownBy(() -> NotificationPayload.builder().attribute("note", value))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"password", "secret", "accessToken", "otp", "recoveryCode"})
    void aSecretsNameCannotCarryAValue(String name) {
        assertThatThrownBy(() -> NotificationPayload.builder().attribute(name, "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void atMostTwentyAttributes() {
        NotificationPayload.Builder builder = NotificationPayload.builder();
        for (int i = 0; i < NotificationPayload.MAX_ATTRIBUTES; i++) {
            builder.attribute("k" + i, "v");
        }

        assertThatThrownBy(() -> builder.attribute("oneMore", "v"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- NotificationMessage ----

    @Test
    void aMessageNeedsARealOrganizationId() {
        assertThatThrownBy(
                        () ->
                                NotificationMessage.builder(null, EMPLOYEE, "SOMETHING_HAPPENED")
                                        .build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                NotificationMessage.builder(
                                                new UUID(0L, 0L), EMPLOYEE, "SOMETHING_HAPPENED")
                                        .build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aMessageNeedsARealEmployeeId() {
        assertThatThrownBy(
                        () -> NotificationMessage.builder(ORG, null, "SOMETHING_HAPPENED").build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                NotificationMessage.builder(
                                                ORG, new UUID(0L, 0L), "SOMETHING_HAPPENED")
                                        .build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(
                        () ->
                                NotificationMessage.builder(ORG, EMPLOYEE, "SOMETHING_HAPPENED")
                                        .build())
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "lower_case", "HAS-DASH", "1_STARTS_WITH_DIGIT"})
    void typesMustBeUpperSnakeCase(String value) {
        assertThatThrownBy(() -> NotificationMessage.builder(ORG, EMPLOYEE, value).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aMissingPayloadBecomesNoPayload() {
        NotificationMessage message =
                NotificationMessage.builder(ORG, EMPLOYEE, "SOMETHING_HAPPENED")
                        .payload(null)
                        .build();

        assertThat(message.payload()).isSameAs(NotificationPayload.none());
    }

    // ---- the API cannot mutate, and cannot take arbitrary objects ----

    @Test
    void theWriterCanOnlyAppend() {
        List<String> publicMethods =
                Arrays.stream(NotificationWriter.class.getDeclaredMethods())
                        .filter(m -> Modifier.isPublic(m.getModifiers()))
                        .map(Method::getName)
                        .toList();

        assertThat(publicMethods).containsExactly("append");
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
                        "replace");

        for (Class<?> type :
                List.of(
                        NotificationWriter.class,
                        NotificationMessage.class,
                        NotificationMessage.Builder.class,
                        NotificationPayload.class,
                        NotificationPayload.Builder.class)) {
            for (Method method : type.getMethods()) {
                String name = method.getName().toLowerCase();
                assertThat(forbidden.stream().filter(name::contains))
                        .as(type.getSimpleName() + "." + method.getName())
                        .isEmpty();
            }
        }
    }

    @Test
    void thePublicApiOnlyTakesReviewedScalarsAndNotificationTypes() {
        Set<Class<?>> allowed =
                Set.of(
                        String.class,
                        long.class,
                        boolean.class,
                        UUID.class,
                        NotificationPayload.class,
                        NotificationMessage.class);

        for (Class<?> type :
                List.of(
                        NotificationWriter.class,
                        NotificationMessage.class,
                        NotificationMessage.Builder.class,
                        NotificationPayload.class,
                        NotificationPayload.Builder.class)) {
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
        for (var component : NotificationMessage.class.getRecordComponents()) {
            assertThat(allowed).as(component.getName()).contains(component.getType());
        }
    }
}
