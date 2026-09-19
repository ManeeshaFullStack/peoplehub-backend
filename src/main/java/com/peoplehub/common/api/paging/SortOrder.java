package com.peoplehub.common.api.paging;

import org.springframework.data.domain.Sort;

/** One validated sort instruction: a property the endpoint allows sorting on, and a direction. */
public record SortOrder(String property, Sort.Direction direction) {}
