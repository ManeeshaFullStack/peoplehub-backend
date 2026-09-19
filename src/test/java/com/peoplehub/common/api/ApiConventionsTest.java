package com.peoplehub.common.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.peoplehub.support.ApiWebTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Cross-cutting API conventions (Spec 13): base path and ISO-8601 UTC instants. */
@ApiWebTest
class ApiConventionsTest {

    @Autowired private MockMvc mvc;

    @Test
    void controllersAreServedUnderTheApiPrefix() throws Exception {
        mvc.perform(get("/api/v1/api-test/instant")).andExpect(status().isOk());
    }

    @Test
    void theSamePathWithoutThePrefixIsNotServed() throws Exception {
        mvc.perform(get("/api-test/instant")).andExpect(status().isNotFound());
    }

    @Test
    void instantsAreIso8601InUtc() throws Exception {
        mvc.perform(get("/api/v1/api-test/instant"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.at").value("2026-03-08T06:59:59.999Z"));
    }
}
