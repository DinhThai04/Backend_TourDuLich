package com.example.tour_service.service;

import com.example.tour_service.dto.request.ApiResponse;
import com.example.tour_service.dto.response.TourPriceResponse;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.stream.LongStream;
import static org.junit.jupiter.api.Assertions.*;

class PriceBatchLoaderTest {
    @Test void largeCatalogUsesBoundedBatches() {
        List<Integer> sizes=new ArrayList<>();
        var prices=PriceBatchLoader.load(LongStream.rangeClosed(1,205).boxed().toList(),request->{
            sizes.add(request.getIds().size());return ApiResponse.<List<TourPriceResponse>>builder().result(request.getIds().stream()
                    .map(id->TourPriceResponse.builder().id(id).build()).toList()).build();
        });
        assertEquals(List.of(100,100,5),sizes);assertEquals(205,prices.size());
    }
    @Test void emptyCatalogDoesNotCallPublicBatchWithEmptyIds() {
        assertTrue(PriceBatchLoader.load(List.of(),request->{fail("Empty batch sent");return null;}).isEmpty());
    }
    @Test void downstreamFailureCannotReturnPartialCatalog() {
        assertThrows(IllegalStateException.class,()->PriceBatchLoader.load(List.of(1L),request->null));
    }
}
