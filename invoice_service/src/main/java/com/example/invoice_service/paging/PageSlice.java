package com.example.invoice_service.paging;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Bounded zero-based page for management lists that keep returning plain arrays.
 * A missing parameter can never turn the query into a full-table dump.
 */
public record PageSlice(int page, int size) {
    private static final int DEFAULT_SIZE = 200;
    private static final int MAX_SIZE = 500;

    public static PageSlice of(Integer page, Integer size) {
        int safeSize = size == null ? DEFAULT_SIZE : Math.max(1, Math.min(size, MAX_SIZE));
        int safePage = page == null ? 0 : Math.max(page, 0);
        return new PageSlice(safePage, safeSize);
    }

    /** Long arithmetic so a large page number cannot overflow into a negative offset. */
    public long offset() {
        return (long) page * size;
    }

    public Pageable pageable(Sort sort) {
        return PageRequest.of(page, size, sort);
    }
}
