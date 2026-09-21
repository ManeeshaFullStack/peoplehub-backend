package com.peoplehub;

import com.peoplehub.support.TestcontainersConfiguration;
import org.springframework.boot.SpringApplication;

/**
 * Local dev entry point: runs the app against throwaway Postgres and Redis containers. Start with
 * {@code ./mvnw spring-boot:test-run}. Requires Docker; needs no environment variables. Runs with
 * the {@code local} profile (readable console, DEBUG for our code).
 */
public class TestPeopleHubApplication {

    public static void main(String[] args) {
        SpringApplication.from(PeopleHubApplication::main)
                .with(TestcontainersConfiguration.class)
                .withAdditionalProfiles("local")
                .run(args);
    }
}
