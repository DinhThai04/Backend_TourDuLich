package com.example.pricing_service.controller;

import com.example.pricing_service.dto.internal.InternalPriceResponse;
import com.example.pricing_service.entity.TourPrice;
import com.example.pricing_service.repository.TourPriceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/internal/pricing/prices")
@RequiredArgsConstructor
public class InternalPricingController {

    private final TourPriceRepository tourPriceRepository;

    @GetMapping("/{id}")
    public ResponseEntity<InternalPriceResponse> getPriceById(@PathVariable Long id) {
        return tourPriceRepository.findById(id)
                .map(price -> ResponseEntity.ok(toInternalResponse(price)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/batch")
    public ResponseEntity<List<InternalPriceResponse>> getPricesBatch(@RequestBody List<Long> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 100 || ids.stream().anyMatch(id -> id == null || id < 1)) {
            return ResponseEntity.badRequest().build();
        }
        List<TourPrice> prices = tourPriceRepository.findAllById(ids.stream().distinct().toList());
        List<InternalPriceResponse> responses = prices.stream()
                .map(this::toInternalResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(responses);
    }

    private InternalPriceResponse toInternalResponse(TourPrice price) {
        return InternalPriceResponse.builder()
                .id(price.getId())
                .adultPrice(price.getAdultPrice())
                .childPrice(price.getChildPrice())
                .toddlerPrice(price.getToddlerPrice())
                .infantPrice(price.getInfantPrice())
                .singleSupplementPrice(price.getSingleSupplementPrice())
                .build();
    }
}