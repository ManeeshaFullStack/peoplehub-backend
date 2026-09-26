package com.peoplehub.support;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.flyway.autoconfigure.FlywayConnectionDetails;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real PostgreSQL and Redis for integration tests and for local dev via {@code ./mvnw
 * spring-boot:test-run} (Spec 14.4, 16.4), with the database connections a deployment has (B0-6/3,
 * b2-8 O7):
 *
 * <ul>
 *   <li>the application connects as the least-privileged runtime role ({@link
 *       TestDatabaseRoles#RUNTIME_ROLE}), so every statement the application runs in a test is
 *       checked against the privileges production grants;
 *   <li>Flyway migrates as the non-superuser owner role ({@link TestDatabaseRoles#OWNER_ROLE}), so
 *       the schema is owned the way it is in production;
 *   <li>test fixtures that need more than the runtime role (rows in any state, cleanup, reading any
 *       table) use a separate, test-only superuser connection, reachable only through {@link
 *       PrivilegedFixture}. It is never a {@code DataSource} bean and never a default autowire
 *       candidate, so no application bean can receive it.
 * </ul>
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    public static final String POSTGRES_IMAGE = "postgres:17-alpine";
    public static final String REDIS_IMAGE = "redis:7-alpine";

    /**
     * A PostgreSQL container with the database roles provisioned (B0-6). Migrations grant to the
     * runtime role and never create it, so every database the application migrates needs it first:
     * anything that starts its own PostgreSQL container must use this rather than {@code new
     * PostgreSQLContainer(...)}.
     */
    public static PostgreSQLContainer newPostgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE))
                .withInitScript(TestDatabaseRoles.INIT_SCRIPT);
    }

    /**
     * Started by Spring Boot's Testcontainers support. No {@code @ServiceConnection}: that would
     * connect the application as the container's superuser; the two beans below say who connects.
     */
    @Bean
    PostgreSQLContainer postgresContainer() {
        return newPostgresContainer();
    }

    /** The application's datasource: the runtime role. */
    @Bean
    JdbcConnectionDetails runtimeRoleConnectionDetails(PostgreSQLContainer postgres) {
        return new JdbcConnectionDetails() {
            @Override
            public String getUsername() {
                return TestDatabaseRoles.RUNTIME_ROLE;
            }

            @Override
            public String getPassword() {
                return TestDatabaseRoles.RUNTIME_PASSWORD;
            }

            @Override
            public String getJdbcUrl() {
                return postgres.getJdbcUrl();
            }
        };
    }

    /** Flyway's connection: the owner role. */
    @Bean
    FlywayConnectionDetails ownerRoleFlywayConnectionDetails(PostgreSQLContainer postgres) {
        return new FlywayConnectionDetails() {
            @Override
            public String getUsername() {
                return TestDatabaseRoles.OWNER_ROLE;
            }

            @Override
            public String getPassword() {
                return TestDatabaseRoles.OWNER_PASSWORD;
            }

            @Override
            public String getJdbcUrl() {
                return postgres.getJdbcUrl();
            }
        };
    }

    /**
     * The privileged fixture connection pool: the container's superuser, on its own small pool,
     * closed with the context. Deliberately not a {@code DataSource} bean, so Spring Boot's
     * datasource, health and metrics support never see it.
     */
    @Bean(destroyMethod = "close", defaultCandidate = false)
    @PrivilegedFixture
    PrivilegedFixtureDatabase privilegedFixtureDatabase(PostgreSQLContainer postgres) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("privileged-fixture");
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(4);
        return new PrivilegedFixtureDatabase(new HikariDataSource(config));
    }

    @Bean(defaultCandidate = false)
    @PrivilegedFixture
    JdbcTemplate privilegedFixtureJdbcTemplate(
            @PrivilegedFixture PrivilegedFixtureDatabase database) {
        return new JdbcTemplate(database.dataSource());
    }

    @Bean(defaultCandidate = false)
    @PrivilegedFixture
    JdbcClient privilegedFixtureJdbcClient(@PrivilegedFixture PrivilegedFixtureDatabase database) {
        return JdbcClient.create(database.dataSource());
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE)).withExposedPorts(6379);
    }

    /** Holder for the privileged pool, so the pool itself is not a {@code DataSource} bean. */
    public record PrivilegedFixtureDatabase(HikariDataSource dataSource) implements AutoCloseable {
        @Override
        public void close() {
            dataSource.close();
        }
    }
}
