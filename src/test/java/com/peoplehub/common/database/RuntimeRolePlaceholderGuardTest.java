package com.peoplehub.common.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The runtime role name is substituted into migration SQL as text, so only a plain lower-case
 * identifier may pass (B0-6/3).
 */
class RuntimeRolePlaceholderGuardTest {

    private static void check(String role) {
        RuntimeRolePlaceholderGuard.requireValidRuntimeRole(
                Map.of(RuntimeRolePlaceholderGuard.PLACEHOLDER, role));
    }

    @ParameterizedTest
    @ValueSource(strings = {"peoplehub_app", "a", "_x", "app1", "svc_peoplehub_runtime_2"})
    void plainLowerCaseIdentifiersPass(String role) {
        assertThatCode(() -> check(role)).doesNotThrowAnyException();
    }

    @Test
    void aSixtyThreeCharacterNameIsTheLongestAllowed() {
        assertThatCode(() -> check("a".repeat(63))).doesNotThrowAnyException();
        assertThatThrownBy(() -> check("a".repeat(64))).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "PeopleHub",
                "peoplehub-app",
                "1app",
                "app name",
                "app;DROP TABLE audit_log",
                "app'--",
                "app\"x",
                "app\n",
                "app$$",
                "app${x}",
                "app.schema",
                "ünï",
                "app,other"
            })
    void anythingThatCouldBecomeSqlIsRefused(String role) {
        assertThatThrownBy(() -> check(role)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aMissingPlaceholderIsRefused() {
        assertThatThrownBy(() -> RuntimeRolePlaceholderGuard.requireValidRuntimeRole(Map.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theRejectedValueIsNotEchoed() {
        String rejected = "app;DROP TABLE audit_log";

        assertThatThrownBy(() -> check(rejected))
                .isInstanceOf(IllegalStateException.class)
                .message()
                .doesNotContain(rejected)
                .contains(RuntimeRolePlaceholderGuard.PLACEHOLDER);
        assertThat(RuntimeRolePlaceholderGuard.ROLE_NAME.pattern())
                .isEqualTo("[a-z_][a-z0-9_]{0,62}");
    }
}
