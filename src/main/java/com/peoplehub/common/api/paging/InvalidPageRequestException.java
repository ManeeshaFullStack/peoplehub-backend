package com.peoplehub.common.api.paging;

import com.peoplehub.common.api.error.ApiFieldError;
import com.peoplehub.common.api.error.ApiProblemException;
import com.peoplehub.common.api.error.ProblemType;
import java.util.List;
import java.util.Map;

/**
 * Raised for a bad {@code page}, {@code size} or {@code sort}. Becomes a 400 with {@code
 * fieldErrors} and, when the sort was the problem, {@code allowedSortFields} (Spec 13.1).
 */
public class InvalidPageRequestException extends ApiProblemException {

    static final String ALLOWED_SORT_FIELDS = "allowedSortFields";

    InvalidPageRequestException(List<ApiFieldError> fieldErrors, List<String> allowedSortFields) {
        super(
                ProblemType.INVALID_PAGE_REQUEST,
                "Invalid pagination or sort parameters.",
                fieldErrors,
                allowedSortFields == null
                        ? Map.of()
                        : Map.of(ALLOWED_SORT_FIELDS, List.copyOf(allowedSortFields)));
    }
}
