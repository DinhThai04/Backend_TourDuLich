package com.example.pricing_service.service;

import com.example.pricing_service.dto.request.TourPriceRequest;
import com.example.pricing_service.dto.response.TourPriceResponse;
import com.example.pricing_service.entity.TourPrice;
import com.example.pricing_service.exception.AppException;
import com.example.pricing_service.exception.ErrorCode;
import com.example.pricing_service.repository.TourPriceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TourPriceService {

    private static final int MAX_LIST_SIZE = 500;

    private final TourPriceRepository repository;

    public TourPriceResponse createPrice(TourPriceRequest request) {
        VndPricePolicy.validate(request);
        TourPrice tourPrice = TourPrice.builder()
                .adultPrice(request.getAdultPrice())
                .childPrice(request.getChildPrice())
                .toddlerPrice(request.getToddlerPrice())
                .infantPrice(request.getInfantPrice())
                .singleSupplementPrice(request.getSingleSupplementPrice())
                .build();

        return toResponse(repository.save(tourPrice));
    }

    @org.springframework.transaction.annotation.Transactional
    public TourPriceResponse updatePrice(Long id, TourPriceRequest request) {
        VndPricePolicy.validate(request);
        // The row stays locked until commit, so concurrent edits are applied one after another.
        TourPrice existing = repository.lockById(id)
                .orElseThrow(() -> new AppException(ErrorCode.PRICE_NOT_EXISTED));

        existing.setAdultPrice(request.getAdultPrice());
        existing.setChildPrice(request.getChildPrice());
        existing.setToddlerPrice(request.getToddlerPrice());
        existing.setInfantPrice(request.getInfantPrice());
        existing.setSingleSupplementPrice(request.getSingleSupplementPrice());

        return toResponse(repository.save(existing));
    }

    public List<TourPriceResponse> getAllPrices() {
        // Admin screens only need the newest price sheets; an unbounded findAll grows with every tour.
        return repository.findAll(PageRequest.of(0, MAX_LIST_SIZE, Sort.by(Sort.Direction.DESC, "id"))).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public TourPriceResponse getPriceById(Long id) {
        TourPrice tourPrice = repository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.PRICE_NOT_EXISTED));
        return toResponse(tourPrice);
    }

    public List<TourPriceResponse> getPricesBatch(List<Long> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 100 || ids.stream().anyMatch(id -> id == null || id < 1))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"Cần từ 1 đến 100 mã bảng giá hợp lệ");
        List<TourPrice> prices = repository.findAllById(ids.stream().distinct().toList());
        return prices.stream().map(this::toResponse).collect(Collectors.toList());
    }

    private TourPriceResponse toResponse(TourPrice tourPrice) {
        return TourPriceResponse.builder()
                .id(tourPrice.getId())
                .adultPrice(tourPrice.getAdultPrice())
                .childPrice(tourPrice.getChildPrice())
                .toddlerPrice(tourPrice.getToddlerPrice())
                .infantPrice(tourPrice.getInfantPrice())
                .singleSupplementPrice(tourPrice.getSingleSupplementPrice())
                .build();
    }
}
