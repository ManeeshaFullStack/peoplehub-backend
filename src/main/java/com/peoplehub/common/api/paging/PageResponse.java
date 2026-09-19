package com.peoplehub.common.api.paging;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * The one response shape for every list endpoint (Spec 13.1): {@code items, page, size,
 * totalElements, totalPages}. No endpoint returns a bare array or Spring's own page serialization.
 */
public record PageResponse<T>(
        List<T> items, int page, int size, long totalElements, int totalPages) {

    public PageResponse {
        items = List.copyOf(items);
    }

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }

    /** Converts entities to DTOs while building the envelope. */
    public static <S, T> PageResponse<T> from(
            Page<S> page, Function<? super S, ? extends T> mapper) {
        return from(page.map(mapper));
    }
}
