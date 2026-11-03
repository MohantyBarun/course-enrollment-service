package com.seatsure.dto;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Stable JSON shape for paginated results. Serialising Spring's PageImpl directly is
 * discouraged because its JSON structure is not guaranteed between versions.
 */
public record PageView<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean last
) {
    /** The same page with each item transformed. */
    public <R> PageView<R> map(java.util.function.Function<T, R> mapper) {
        return new PageView<>(content.stream().map(mapper).toList(), page, size, totalElements, totalPages, last);
    }

    public static <T> PageView<T> from(Page<T> page) {
        return new PageView<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isLast()
        );
    }
}
