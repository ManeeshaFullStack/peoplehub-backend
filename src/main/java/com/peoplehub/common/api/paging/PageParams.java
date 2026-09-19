package com.peoplehub.common.api.paging;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the pagination contract of one list endpoint (Spec 13.1: "everything else states its own
 * default"). Put it on a {@link PageQuery} handler parameter:
 *
 * <pre>{@code
 * @GetMapping
 * PageResponse<EmployeeDto> list(
 *         @PageParams(defaultSize = 25, defaultSort = "name,asc", sortable = {"name", "joinDate"})
 *                 PageQuery query) { ... }
 * }</pre>
 *
 * <p>Sort names are the names clients send, and become the sort properties passed on to the
 * repository, so they must match entity property names. Anything not listed in {@link #sortable()}
 * is rejected with a 400 that names the allowed fields.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface PageParams {

    /** Page size used when the client sends none. Between 1 and {@link PageQuery#MAX_SIZE}. */
    int defaultSize() default PageQuery.DEFAULT_SIZE;

    /** Sort used when the client sends none: {@code "field"} or {@code "field,asc|desc"}. */
    String defaultSort() default "";

    /** Fields clients may sort by. Empty means the endpoint is not sortable. */
    String[] sortable() default {};
}
