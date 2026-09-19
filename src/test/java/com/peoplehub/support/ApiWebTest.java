package com.peoplehub.support;

import com.peoplehub.common.api.testsupport.SampleApiController;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Web-layer-only test of the API standards against {@link SampleApiController}. Loads just the MVC
 * infrastructure (advice, filter, web config), so it needs neither Docker nor a database and runs
 * in a fraction of a second.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@WebMvcTest(SampleApiController.class)
@ActiveProfiles("api-test")
public @interface ApiWebTest {}
