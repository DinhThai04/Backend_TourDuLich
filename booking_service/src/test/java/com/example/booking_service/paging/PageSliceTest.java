package com.example.booking_service.paging;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PageSliceTest {
    @Test void defaultsToFirstPageOfTwoHundred() {
        var slice = PageSlice.of(null, null);
        assertEquals(0, slice.page());
        assertEquals(200, slice.size());
        assertEquals(0L, slice.offset());
    }

    @Test void clampsNegativePageAndUnboundedSize() {
        assertEquals(0, PageSlice.of(-5, 0).page());
        assertEquals(1, PageSlice.of(-5, 0).size());
        assertEquals(500, PageSlice.of(0, 10_000).size());
        assertEquals(0L, PageSlice.of(-1, 200).offset());
    }

    @Test void offsetUsesLongArithmeticAndPageableStaysZeroBased() {
        var slice = PageSlice.of(Integer.MAX_VALUE, 500);
        assertEquals(1_073_741_823_500L, slice.offset());
        var pageable = slice.pageable(org.springframework.data.domain.Sort.by("id"));
        assertEquals(PageRequest.of(Integer.MAX_VALUE, 500).getOffset(), pageable.getOffset());
    }
}
