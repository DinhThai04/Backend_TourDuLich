package com.example.search_service.controller;

import com.example.search_service.document.TourDocument;
import com.example.search_service.dto.ApiResponse;
import com.example.search_service.exception.AppException;
import com.example.search_service.exception.ErrorCode;
import com.example.search_service.service.TourSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/tours")
@RequiredArgsConstructor
public class TourSearchController {
    private final TourSearchService service;
    private final com.example.search_service.service.CatalogSynchronizer synchronizer;

    @PostMapping("/sync")
    public ApiResponse<TourSearchService.SyncResult> sync() {
        return ApiResponse.<TourSearchService.SyncResult>builder().result(synchronizer.sync()).message("Catalog synchronized").build();
    }

    @GetMapping
    public ApiResponse<Iterable<TourDocument>> getAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        return ApiResponse.<Iterable<TourDocument>>builder()
                .result(service.findAll(page, size))
                .message("Get successfully")
                .build();
    }

    @GetMapping("/search")
    public ApiResponse<Page<TourDocument>> filterTours(
            @RequestParam(required = false) String destinationLocation,
            @RequestParam(required = false) String basePrice,
            @RequestParam(required = false) String departureDate,
            @RequestParam int page,
            @RequestParam int size
    ) {

        Page<TourDocument> result = service.filterByDestinationAndPriceAndDate(destinationLocation, basePrice, departureDate, page, size);

        return ApiResponse.<Page<TourDocument>>builder()
                .message("Filter successfully")
                .result(result)
                .build();
    }
}
