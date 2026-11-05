package com.example.tour_service.service;

import com.example.tour_service.dto.request.ApiResponse;
import com.example.tour_service.dto.request.TourPriceBatchRequest;
import com.example.tour_service.dto.response.TourPriceResponse;
import java.util.*;
import java.util.function.Function;

/** Internal catalogs can exceed the public pricing endpoint's 100-ID request limit. */
public final class PriceBatchLoader {
    private PriceBatchLoader() {}
    public static List<TourPriceResponse> load(List<Long> ids,
            Function<TourPriceBatchRequest, ApiResponse<List<TourPriceResponse>>> fetch) {
        var distinct = ids.stream().distinct().toList();
        List<TourPriceResponse> result = new ArrayList<>();
        for (int start = 0; start < distinct.size(); start += 100) {
            var request = new TourPriceBatchRequest();
            request.setIds(distinct.subList(start, Math.min(start + 100, distinct.size())));
            var response = fetch.apply(request);
            if (response == null || response.getCode() != 1000 || response.getResult() == null)
                throw new IllegalStateException("Không tải được bảng giá");
            result.addAll(response.getResult());
        }
        return result;
    }

    /**
     * Display paths cannot afford a pricing outage: tours already store their own base price,
     * so an unavailable price list degrades the response instead of failing the whole page.
     */
    public static List<TourPriceResponse> loadAvailable(List<Long> ids,
            Function<TourPriceBatchRequest, ApiResponse<List<TourPriceResponse>>> fetch) {
        try {
            return load(ids, fetch);
        } catch (RuntimeException unavailable) {
            return List.of();
        }
    }

    /** Same contract as {@link #loadAvailable} for a single tour's price. */
    public static TourPriceResponse oneOrNull(Long id,
            Function<Long, ApiResponse<TourPriceResponse>> fetch) {
        if (id == null) return null;
        try {
            var response = fetch.apply(id);
            return response == null || response.getCode() != 1000 || response.getResult() == null
                    ? null : response.getResult();
        } catch (RuntimeException unavailable) {
            return null;
        }
    }
}
