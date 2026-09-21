package com.peoplehub.common.database;

import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Keeps the runtime database role name safe to use in migrations (B0-6/3, {@code V3__audit_log}).
 *
 * <p>Migrations grant privileges to the application's runtime role, whose name comes from the
 * environment ({@code PEOPLEHUB_DB_RUNTIME_ROLE}) through the Flyway placeholder {@value
 * #PLACEHOLDER}. Flyway substitutes placeholders as plain text, before the database sees the SQL,
 * so an unchecked value would become executable SQL. This guard runs when Flyway is configured,
 * before it has touched the database, and refuses anything outside a conservative PostgreSQL
 * identifier pattern: the application does not start.
 *
 * <p>Two more layers sit behind it in the migration itself: the name is checked again with the same
 * pattern inside the migration, and every statement uses it through {@code format('%I')}, which
 * quotes it as an identifier. Only a run that bypasses this application (for example Flyway's own
 * command line with a hand-made placeholder) relies on those alone.
 *
 * <p>The rejected value is deliberately not echoed in the error.
 */
@Configuration(proxyBeanMethods = false)
public class RuntimeRolePlaceholderGuard {

    /** Name of the Flyway placeholder, {@code spring.flyway.placeholders.runtime_role}. */
    public static final String PLACEHOLDER = "runtime_role";

    /** Lower-case PostgreSQL identifier, at most 63 characters (its limit), no quoting needed. */
    public static final Pattern ROLE_NAME = Pattern.compile("[a-z_][a-z0-9_]{0,62}");

    @Bean
    FlywayConfigurationCustomizer runtimeRolePlaceholderCustomizer() {
        return configuration -> requireValidRuntimeRole(configuration.getPlaceholders());
    }

    /** Fails unless the placeholders hold a plain runtime role name. */
    static void requireValidRuntimeRole(Map<String, String> placeholders) {
        String role = placeholders.get(PLACEHOLDER);
        if (role == null || !ROLE_NAME.matcher(role).matches()) {
            throw new IllegalStateException(
                    "Flyway placeholder '"
                            + PLACEHOLDER
                            + "' (PEOPLEHUB_DB_RUNTIME_ROLE) must match "
                            + ROLE_NAME.pattern());
        }
    }
}
