package com.peoplehub.common.api.paging;

import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * A validated pagination and sort request (Spec 13.1). Controllers receive it as a parameter
 * annotated with {@link PageParams}; by the time a handler runs, page, size and sort are already
 * checked, so it can be passed straight to a repository through {@link #toPageable()}.
 *
 * @param page zero-based page index
 * @param size page size, between 1 and {@value #MAX_SIZE}
 * @param sort sort orders in the order the client gave them (may be empty)
 */
public record PageQuery(int page, int size, List<SortOrder> sort) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    public PageQuery {
        sort = List.copyOf(sort);
    }

    public Pageable toPageable() {
        if (sort.isEmpty()) {
            return PageRequest.of(page, size);
        }
        List<Sort.Order> orders =
                sort.stream()
                        .map(order -> new Sort.Order(order.direction(), order.property()))
                        .toList();
        return PageRequest.of(page, size, Sort.by(orders));
    }
}
