package com.peoplehub.common.api.paging;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.hasKey;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.peoplehub.support.ApiWebTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** A list endpoint end to end: parameters in, envelope out (Spec 13.1). */
@ApiWebTest
class PagedEndpointTest {

    private static final String ITEMS = "/api/v1/api-test/items";

    @Autowired private MockMvc mvc;

    @Test
    void returnsExactlyTheStandardEnvelope() throws Exception {
        mvc.perform(get(ITEMS))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", aMapWithSize(5)))
                .andExpect(jsonPath("$", hasKey("items")))
                .andExpect(jsonPath("$", hasKey("page")))
                .andExpect(jsonPath("$", hasKey("size")))
                .andExpect(jsonPath("$", hasKey("totalElements")))
                .andExpect(jsonPath("$", hasKey("totalPages")))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.totalElements").value(45))
                .andExpect(jsonPath("$.totalPages").value(5))
                .andExpect(jsonPath("$.items.length()").value(10))
                .andExpect(jsonPath("$.items[0].name").value("item-01"));
    }

    @Test
    void appliesTheRequestedSort() throws Exception {
        mvc.perform(get(ITEMS).param("sort", "name,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("item-45"));
        mvc.perform(get(ITEMS).param("sort", "joinedAt,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("item-45"));
    }

    @Test
    void lastPageIsPartial() throws Exception {
        mvc.perform(get(ITEMS).param("page", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(5))
                .andExpect(jsonPath("$.items[0].name").value("item-41"));
    }

    @Test
    void aPagePastTheEndIsAnEmptyPageNotAnError() throws Exception {
        mvc.perform(get(ITEMS).param("page", "99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.page").value(99))
                .andExpect(jsonPath("$.totalElements").value(45))
                .andExpect(jsonPath("$.totalPages").value(5));
    }

    @Test
    void honoursTheRequestedSize() throws Exception {
        mvc.perform(get(ITEMS).param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(45))
                .andExpect(jsonPath("$.size").value(100))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    void anUnknownSortFieldNeverReachesTheDataLayer() throws Exception {
        mvc.perform(get(ITEMS).param("sort", "password"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.allowedSortFields[0]").value("name"))
                .andExpect(jsonPath("$.allowedSortFields[1]").value("joinedAt"));
    }
}
