package com.example.pricing_service.service;

import com.example.pricing_service.dto.request.TourPriceRequest;
import com.example.pricing_service.dto.response.TourPriceResponse;
import com.example.pricing_service.entity.TourPrice;
import com.example.pricing_service.exception.AppException;
import com.example.pricing_service.exception.ErrorCode;
import com.example.pricing_service.repository.TourPriceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TourPriceService {

    private final TourPriceRepository repository;

    public TourPriceResponse createPrice(TourPriceRequest request) {
        TourPrice tourPrice = TourPrice.builder()
                .adultPrice(request.getAdultPrice())
                .childPrice(request.getChildPrice())
                .toddlerPrice(request.getToddlerPrice())
                .infantPrice(request.getInfantPrice())
                .singleSupplementPrice(request.getSingleSupplementPrice())
                .build();

        return toResponse(repository.save(tourPrice));
    }

    public TourPriceResponse updatePrice(Long id, TourPriceRequest request) {
        TourPrice existing = repository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.INVALID_KEY));

        existing.setAdultPrice(request.getAdultPrice());
        existing.setChildPrice(request.getChildPrice());
        existing.setToddlerPrice(request.getToddlerPrice());
        existing.setInfantPrice(request.getInfantPrice());
        existing.setSingleSupplementPrice(request.getSingleSupplementPrice());

        return toResponse(repository.save(existing));
    }

//    public void deletePrice(Long id) {
//        if (!repository.existsById(id)) {
//            throw new AppException(ErrorCode.INVALID_KEY);
//        }
//        repository.deleteById(id);
//    }

    public List<TourPriceResponse> getAllPrices() {
        return repository.findAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public TourPriceResponse getPriceById(Long id) {
        TourPrice tourPrice = repository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.INVALID_KEY));
        return toResponse(tourPrice);
    }

    public List<TourPriceResponse> getPricesBatch(List<Long> ids) {
        List<TourPrice> prices = repository.findAllById(ids);
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
