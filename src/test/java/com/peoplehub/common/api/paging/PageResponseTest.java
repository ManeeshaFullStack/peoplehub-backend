package com.peoplehub.common.api.paging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/** Plain unit tests: no Spring context. */
class PageResponseTest {

    @Test
    void copiesTheFieldsOfASpringPage() {
        PageImpl<String> page = new PageImpl<>(List.of("a", "b"), PageRequest.of(2, 2), 7);

        PageResponse<String> response = PageResponse.from(page);

        assertThat(response.items()).containsExactly("a", "b");
        assertThat(response.page()).isEqualTo(2);
        assertThat(response.size()).isEqualTo(2);
        assertThat(response.totalElements()).isEqualTo(7);
        assertThat(response.totalPages()).isEqualTo(4);
    }

    @Test
    void anEmptyResultHasZeroTotalPages() {
        PageResponse<String> response =
                PageResponse.from(new PageImpl<>(List.<String>of(), PageRequest.of(0, 20), 0));

        assertThat(response.items()).isEmpty();
        assertThat(response.totalElements()).isZero();
        assertThat(response.totalPages()).isZero();
    }

    @Test
    void mapsEntitiesToDtos() {
        PageImpl<Integer> page = new PageImpl<>(List.of(1, 2, 3), PageRequest.of(0, 3), 3);

        PageResponse<String> response = PageResponse.from(page, n -> "n" + n);

        assertThat(response.items()).containsExactly("n1", "n2", "n3");
        assertThat(response.totalElements()).isEqualTo(3);
    }

    @Test
    void itemsAreDefensivelyCopied() {
        List<String> mutable = new ArrayList<>(List.of("a"));
        PageResponse<String> response = new PageResponse<>(mutable, 0, 20, 1, 1);
        mutable.add("b");

        assertThat(response.items()).containsExactly("a");
    }
}
