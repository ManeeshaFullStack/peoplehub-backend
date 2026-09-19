package com.peoplehub.common.api.paging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.peoplehub.common.api.paging.PageQueryArgumentResolver.Config;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/** Plain unit tests: no Spring context. */
class PageQueryUnitTest {

    @Test
    void toPageableCarriesPageSizeAndEverySortOrderInSequence() {
        PageQuery query =
                new PageQuery(
                        3,
                        40,
                        List.of(
                                new SortOrder("name", Sort.Direction.DESC),
                                new SortOrder("joinDate", Sort.Direction.ASC)));

        Pageable pageable = query.toPageable();

        assertThat(pageable.getPageNumber()).isEqualTo(3);
        assertThat(pageable.getPageSize()).isEqualTo(40);
        assertThat(pageable.getSort().stream().map(o -> o.getProperty() + ":" + o.getDirection()))
                .containsExactly("name:DESC", "joinDate:ASC");
    }

    @Test
    void toPageableWithoutSortIsUnsorted() {
        assertThat(new PageQuery(0, 20, List.of()).toPageable().getSort().isUnsorted()).isTrue();
    }

    @Test
    void sortListIsDefensivelyCopied() {
        java.util.ArrayList<SortOrder> mutable = new java.util.ArrayList<>();
        PageQuery query = new PageQuery(0, 20, mutable);
        mutable.add(new SortOrder("name", Sort.Direction.ASC));

        assertThat(query.sort()).isEmpty();
    }

    @Test
    void aBadDefaultSortIsADeveloperErrorNotAClientError() {
        Config config = new Config(10, "nope,asc", Set.of("name"));

        assertThatThrownBy(() -> PageQueryArgumentResolver.parse(null, null, null, config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("defaultSort");
    }

    @Test
    void aDefaultSizeOutsideTheAllowedRangeIsADeveloperError() {
        assertThatThrownBy(
                        () ->
                                PageQueryArgumentResolver.parse(
                                        null, null, null, new Config(101, "", Set.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("defaultSize");
        assertThatThrownBy(
                        () ->
                                PageQueryArgumentResolver.parse(
                                        null, null, null, new Config(0, "", Set.of())))
                .isInstanceOf(IllegalStateException.class);
    }
}
