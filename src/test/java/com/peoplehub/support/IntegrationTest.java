package com.peoplehub.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Full application context backed by real PostgreSQL and Redis containers. Use for any test that
 * needs the database, Flyway or Redis. Docker must be running.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest
@Import(TestcontainersConfiguration.class)
public @interface IntegrationTest {}
