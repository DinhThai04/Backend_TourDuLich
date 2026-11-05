package com.example.auth_service.paging;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PageSliceTest {
    @Test void defaultsToFirstPageOfTwoHundred() {
        var slice = PageSlice.of(null, null);
        assertEquals(0, slice.page());
        assertEquals(200, slice.size());
        assertEquals(PageRequest.of(0, 200), slice.pageable(Sort.unsorted()));
    }

    @Test void clampsNegativePageAndUnboundedSize() {
        assertEquals(0, PageSlice.of(-5, 0).page());
        assertEquals(1, PageSlice.of(-5, 0).size());
        assertEquals(500, PageSlice.of(0, 10_000).size());
    }

    @Test void pageableStaysZeroBased() {
        var pageable = PageSlice.of(2, 100).pageable(Sort.by("id"));
        assertEquals(PageRequest.of(2, 100, Sort.by("id")), pageable);
    }
}
