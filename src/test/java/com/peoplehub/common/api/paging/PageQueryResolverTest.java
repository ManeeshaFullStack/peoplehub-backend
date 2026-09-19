package com.peoplehub.common.api.paging;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.peoplehub.support.ApiWebTest;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

@ApiWebTest
class PageQueryResolverTest {

    private static final String ECHO = "/api/v1/api-test/query-echo";
    private static final String UNSORTABLE = "/api/v1/api-test/unsortable";
    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String INVALID_PAGE_TYPE = "urn:peoplehub:problem:invalid-page-request";

    @Autowired private MockMvc mvc;

    @Test
    void usesTheEndpointDefaultsWhenNothingIsSent() throws Exception {
        mvc.perform(get(ECHO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(25))
                .andExpect(jsonPath("$.sort").value(contains("joinedAt:DESC")));
    }

    @Test
    void usesTheFrameworkDefaultsWhenTheAnnotationDeclaresNone() throws Exception {
        mvc.perform(get(UNSORTABLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(PageQuery.DEFAULT_SIZE))
                .andExpect(jsonPath("$.sort").value(empty()));
    }

    @Test
    void readsExplicitValuesAndKeepsTheOrderOfSortFields() throws Exception {
        mvc.perform(
                        get(ECHO)
                                .param("page", "2")
                                .param("size", "50")
                                .param("sort", "name,desc")
                                .param("sort", "status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(50))
                .andExpect(jsonPath("$.sort").value(contains("name:DESC", "status:ASC")));
    }

    @Test
    void acceptsTheMaximumSizeAndDirectionInAnyCase() throws Exception {
        mvc.perform(get(ECHO).param("size", "100").param("sort", "name,DeSc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100))
                .andExpect(jsonPath("$.sort").value(contains("name:DESC")));
    }

    static Stream<Arguments> rejectedParameters() {
        return Stream.of(
                Arguments.of("size", "0", "size"),
                Arguments.of("size", "101", "size"),
                Arguments.of("size", "-5", "size"),
                Arguments.of("size", "abc", "size"),
                Arguments.of("size", "1.5", "size"),
                Arguments.of("size", "", "size"),
                Arguments.of("page", "-1", "page"),
                Arguments.of("page", "abc", "page"),
                Arguments.of("page", "", "page"),
                Arguments.of("page", "99999999999", "page"),
                Arguments.of("sort", "nope", "sort"),
                Arguments.of("sort", "NAME", "sort"),
                Arguments.of("sort", "name,sideways", "sort"),
                Arguments.of("sort", "name,asc,extra", "sort"),
                Arguments.of("sort", "", "sort"),
                Arguments.of("sort", ",asc", "sort"));
    }

    @ParameterizedTest(name = "{0}={1} is rejected")
    @MethodSource("rejectedParameters")
    void rejectsInvalidValuesWithAProblemNamingTheField(String name, String value, String field)
            throws Exception {
        mvc.perform(get(ECHO).param(name, value))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(INVALID_PAGE_TYPE))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors[0].field").value(field))
                .andExpect(jsonPath("$.fieldErrors[0].message").value(not(containsString("null"))));
    }

    @Test
    void sizeAboveTheMaximumIsRejectedNotClampedAndPointsToExports() throws Exception {
        mvc.perform(get(ECHO).param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].message").value(containsString("export")));
    }

    @Test
    void rejectsARepeatedSortField() throws Exception {
        mvc.perform(get(ECHO).param("sort", "name").param("sort", "name,desc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("sort"))
                .andExpect(
                        jsonPath("$.fieldErrors[0].message")
                                .value(containsString("more than once")));
    }

    @Test
    void reportsEveryProblemAtOnceInAStableOrder() throws Exception {
        mvc.perform(get(ECHO).param("size", "0").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.length()").value(2))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("page"))
                .andExpect(jsonPath("$.fieldErrors[1].field").value("size"));
    }

    @Test
    void listsTheAllowedSortFieldsInDeclaredOrderWhenTheSortWasWrong() throws Exception {
        mvc.perform(get(ECHO).param("sort", "nope"))
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.allowedSortFields")
                                .value(contains("name", "joinedAt", "status")));
    }

    @Test
    void omitsAllowedSortFieldsWhenTheSortWasFine() throws Exception {
        mvc.perform(get(ECHO).param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.allowedSortFields").doesNotExist());
    }

    @Test
    void anEndpointWithoutSortableFieldsRejectsAnySortAndSaysNothingIsAllowed() throws Exception {
        mvc.perform(get(UNSORTABLE).param("sort", "name"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.allowedSortFields").value(empty()));
    }

    @Test
    void neverEchoesTheRejectedValue() throws Exception {
        mvc.perform(
                        get(ECHO)
                                .param("sort", "secret-jane@example.com")
                                .param("size", "jane@example.com"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString("jane"))));
    }
}
