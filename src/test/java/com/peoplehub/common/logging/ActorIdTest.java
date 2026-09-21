package com.peoplehub.common.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;

class ActorIdTest {

    @AfterEach
    void cleanUp() {
        ActorId.clear();
    }

    @Test
    void recordsTheActorInMdcUnderTheDocumentedKey() {
        ActorId.set("42");

        assertThat(MDC.get("actorId")).isEqualTo("42");
        assertThat(ActorId.current()).isEqualTo("42");
    }

    @Test
    void clearRemovesTheActor() {
        ActorId.set(ActorId.SYSTEM);
        ActorId.clear();

        assertThat(ActorId.current()).isNull();
    }

    @ParameterizedTest(name = "[{0}] is accepted")
    @ValueSource(
            strings = {
                "42",
                "550e8400-e29b-41d4-a716-446655440000",
                "SYSTEM",
                "job:month-end-lock",
                "anonymous"
            })
    void acceptsEmployeeIdsAndJobNames(String id) {
        ActorId.set(id);

        assertThat(ActorId.current()).isEqualTo(id);
    }

    @ParameterizedTest(name = "[{0}] is rejected")
    @ValueSource(
            strings = {
                "",
                " ",
                "jane doe",
                "jane.doe@example.com",
                "line\nbreak",
                "carriage\rreturn",
                "quote\"d",
            })
    void rejectsAnythingThatIsNotAPlainId(String notAnId) {
        assertThatThrownBy(() -> ActorId.set(notAnId)).isInstanceOf(IllegalArgumentException.class);
        assertThat(ActorId.current()).isNull();
    }

    @Test
    void rejectsNullAndOversizedIds() {
        assertThatThrownBy(() -> ActorId.set(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ActorId.set("x".repeat(65)))
                .isInstanceOf(IllegalArgumentException.class);
        ActorId.set("x".repeat(64));
        assertThat(ActorId.current()).hasSize(64);
    }
}
