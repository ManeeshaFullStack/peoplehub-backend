package com.peoplehub.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * Selects the privileged, test-only {@code JdbcTemplate} for fixtures (b2-8 C2, owner decision O7):
 * setup, cleanup and assertions that legitimately need more than the application's runtime role
 * (inserting rows in any state, deleting between tests, reading any table). It connects as the
 * PostgreSQL container's superuser on its own private pool, never as the application.
 *
 * <p>The bean is not a default autowire candidate, so nothing receives it unless it asks with this
 * qualifier: every application bean, including Spring Boot's own {@code JdbcTemplate} and {@code
 * JdbcClient}, keeps the runtime role's datasource. Use it only for fixtures, never to exercise
 * application behaviour:
 *
 * <pre>{@code
 * @Autowired @PrivilegedFixture private JdbcTemplate jdbc;
 * }</pre>
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Qualifier
public @interface PrivilegedFixture {}
