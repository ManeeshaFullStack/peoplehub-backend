package com.peoplehub.common.api;

import com.peoplehub.common.api.paging.PageQueryArgumentResolver;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web conventions shared by every endpoint (Spec 13): the {@value #API_PREFIX} base path and the
 * pagination argument resolver.
 */
@Configuration(proxyBeanMethods = false)
public class WebConfig implements WebMvcConfigurer {

    public static final String API_PREFIX = "/api/v1";

    /**
     * Every {@code @RestController} in this application is served under {@value #API_PREFIX}, so
     * controllers declare only their own path (for example {@code /admin/employees}). Framework
     * controllers such as springdoc's are not in our package and stay where they are.
     */
    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        // Selectors inside one HandlerTypePredicate are combined with OR, so the package check and
        // the annotation check are two predicates joined with AND. With OR, springdoc's own
        // controllers (also @RestController) would be moved under the prefix as well.
        configurer.addPathPrefix(
                API_PREFIX,
                HandlerTypePredicate.forBasePackage("com.peoplehub")
                        .and(HandlerTypePredicate.forAnnotation(RestController.class)));
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new PageQueryArgumentResolver());
    }
}
