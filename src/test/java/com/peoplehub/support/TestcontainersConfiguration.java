package com.peoplehub.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real PostgreSQL and Redis for integration tests and for local dev via {@code ./mvnw
 * spring-boot:test-run} (Spec 14.4, 16.4). {@link ServiceConnection} wires the datasource and Redis
 * connection details automatically, so no environment variables are needed.
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

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return newPostgresContainer();
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE)).withExposedPorts(6379);
    }
}
