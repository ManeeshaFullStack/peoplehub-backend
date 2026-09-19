package com.peoplehub.common.api.paging;

import com.peoplehub.common.api.error.ApiFieldError;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.core.MethodParameter;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Turns {@code page}, {@code size} and repeatable {@code sort=field,direction} query parameters
 * into a validated {@link PageQuery} (Spec 13.1).
 *
 * <p>Nothing is silently corrected: an out-of-range size, a non-numeric page, an unknown or
 * repeated sort field, or a bad direction is a 400 listing every problem found (and the allowed
 * sort fields when the sort was wrong). Rejected values are never echoed back.
 */
public class PageQueryArgumentResolver implements HandlerMethodArgumentResolver {

    private static final Pattern WHOLE_NUMBER = Pattern.compile("\\d{1,9}");
    private static final String SORT_FORMAT_MESSAGE = "must be 'field' or 'field,asc|desc'";

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return PageQuery.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) {
        Config config = Config.from(parameter.getParameterAnnotation(PageParams.class));
        return parse(
                webRequest.getParameter("page"),
                webRequest.getParameter("size"),
                webRequest.getParameterValues("sort"),
                config);
    }

    /** Endpoint-level settings, taken from {@link PageParams} (or the defaults if it is absent). */
    record Config(int defaultSize, String defaultSort, Set<String> sortable) {

        static Config from(PageParams annotation) {
            if (annotation == null) {
                return new Config(PageQuery.DEFAULT_SIZE, "", Set.of());
            }
            return new Config(
                    annotation.defaultSize(),
                    annotation.defaultSort(),
                    new LinkedHashSet<>(Arrays.asList(annotation.sortable())));
        }
    }

    static PageQuery parse(String rawPage, String rawSize, String[] rawSort, Config config) {
        requireValidConfig(config);
        List<ApiFieldError> errors = new ArrayList<>();

        int page = parsePage(rawPage, errors);
        int size = parseSize(rawSize, config.defaultSize(), errors);

        boolean sortFailed = false;
        List<SortOrder> sort;
        if (rawSort == null || rawSort.length == 0) {
            sort = config.defaultSort().isBlank() ? List.of() : parseDefaultSort(config);
        } else {
            int errorsBefore = errors.size();
            sort = parseSort(rawSort, config.sortable(), errors);
            sortFailed = errors.size() > errorsBefore;
        }

        if (!errors.isEmpty()) {
            throw new InvalidPageRequestException(
                    errors, sortFailed ? List.copyOf(config.sortable()) : null);
        }
        return new PageQuery(page, size, sort);
    }

    private static int parsePage(String raw, List<ApiFieldError> errors) {
        if (raw == null) {
            return 0;
        }
        if (!WHOLE_NUMBER.matcher(raw).matches()) {
            errors.add(new ApiFieldError("page", "must be a whole number, 0 or greater"));
            return 0;
        }
        return Integer.parseInt(raw);
    }

    private static int parseSize(String raw, int defaultSize, List<ApiFieldError> errors) {
        if (raw == null) {
            return defaultSize;
        }
        if (!WHOLE_NUMBER.matcher(raw).matches()) {
            errors.add(
                    new ApiFieldError(
                            "size", "must be a whole number between 1 and " + PageQuery.MAX_SIZE));
            return defaultSize;
        }
        int size = Integer.parseInt(raw);
        if (size < 1) {
            errors.add(
                    new ApiFieldError(
                            "size", "must be a whole number between 1 and " + PageQuery.MAX_SIZE));
        } else if (size > PageQuery.MAX_SIZE) {
            errors.add(
                    new ApiFieldError(
                            "size",
                            "must be at most "
                                    + PageQuery.MAX_SIZE
                                    + "; use an export for larger result sets"));
        }
        return size;
    }

    private static List<SortOrder> parseSort(
            String[] rawSort, Set<String> sortable, List<ApiFieldError> errors) {
        List<SortOrder> orders = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String raw : rawSort) {
            String[] parts = raw.split(",", -1);
            boolean wellFormed =
                    parts.length <= 2
                            && !parts[0].isBlank()
                            && (parts.length == 1 || isDirection(parts[1]));
            if (!wellFormed) {
                errors.add(new ApiFieldError("sort", SORT_FORMAT_MESSAGE));
                continue;
            }
            String property = parts[0];
            if (!sortable.contains(property)) {
                errors.add(
                        new ApiFieldError(
                                "sort",
                                "contains a field that cannot be sorted on; see allowedSortFields"));
                continue;
            }
            if (!seen.add(property)) {
                errors.add(new ApiFieldError("sort", "'" + property + "' is given more than once"));
                continue;
            }
            orders.add(new SortOrder(property, direction(parts.length == 2 ? parts[1] : "asc")));
        }
        return orders;
    }

    /**
     * The default sort is written by a developer, so a mistake there is a bug, not a client error.
     */
    private static List<SortOrder> parseDefaultSort(Config config) {
        List<ApiFieldError> errors = new ArrayList<>();
        List<SortOrder> sort =
                parseSort(new String[] {config.defaultSort()}, config.sortable(), errors);
        if (!errors.isEmpty()) {
            throw new IllegalStateException(
                    "@PageParams defaultSort '"
                            + config.defaultSort()
                            + "' must be 'field' or 'field,asc|desc' and its field must be listed in sortable "
                            + config.sortable());
        }
        return sort;
    }

    private static void requireValidConfig(Config config) {
        if (config.defaultSize() < 1 || config.defaultSize() > PageQuery.MAX_SIZE) {
            throw new IllegalStateException(
                    "@PageParams defaultSize must be between 1 and " + PageQuery.MAX_SIZE);
        }
    }

    private static boolean isDirection(String value) {
        return "asc".equalsIgnoreCase(value) || "desc".equalsIgnoreCase(value);
    }

    private static Sort.Direction direction(String value) {
        return Sort.Direction.valueOf(value.toUpperCase(Locale.ROOT));
    }
}
