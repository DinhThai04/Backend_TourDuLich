package com.example.tour_service.service;

import com.example.tour_service.dto.request.TourPriceBatchRequest;
import com.example.tour_service.repository.httpClient.PricingClient;
import com.example.tour_service.dto.request.ApiResponse;
import com.example.tour_service.dto.request.TourDepartureRequest;
import com.example.tour_service.dto.response.TourDepartureResponse;
import com.example.tour_service.dto.response.TourPriceResponse;
import com.example.tour_service.entity.Tour;
import com.example.tour_service.entity.TourDeparture;
import com.example.tour_service.exception.AppException;
import com.example.tour_service.exception.ErrorCode;
import com.example.tour_service.repository.TourDepartureRepository;
import com.example.tour_service.repository.TourRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TourDepartureService {
    private final TourDepartureRepository tourDepartureRepository;
    private final TourRepository tourRepository;
    private final PricingClient pricingClient;

    public TourDepartureResponse getTourDepartureById(int id){
        TourDeparture tourDeparture = tourDepartureRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.INVALID_KEY));

        TourDepartureResponse response = toResponse(tourDeparture);
        ApiResponse<TourPriceResponse> priceResp = pricingClient.getPriceById(tourDeparture.getTour().getTourPriceId());
        response.setTourPrice(priceResp.getResult());

        return response;
    }

    public List<TourDepartureResponse> getAllTourDeparture() {
        List<TourDeparture> allDepartures = tourDepartureRepository.findAll();

        List<Long> departurePriceIds = allDepartures.stream()
                .map(td -> td.getTour().getTourPriceId())
                .distinct()
                .toList();

        TourPriceBatchRequest depPriceBatchReq = new TourPriceBatchRequest();
        depPriceBatchReq.setIds(departurePriceIds);
        List<TourPriceResponse> depPriceList = pricingClient.getPricesBatch(depPriceBatchReq).getResult();

        Map<Long, TourPriceResponse> depPriceMap = depPriceList.stream()
                .collect(Collectors.toMap(TourPriceResponse::getId, p -> p));

        return allDepartures.stream()
                .map(td -> {
                    TourDepartureResponse tdResp = toResponse(td);
                    tdResp.setTourPrice(depPriceMap.get(td.getTour().getTourPriceId()));
                    return tdResp;
                })
                .toList();
    }


    public List<TourDepartureResponse> getTourDepartureByTourId(int id){
        return tourDepartureRepository.findByTourId(id).stream()
                .filter(tourDeparture -> tourDeparture.getDepartureDate().isAfter(LocalDateTime.now()))
                .map(tourDeparture -> {
                    TourDepartureResponse response = toResponse(tourDeparture);
                    ApiResponse<TourPriceResponse> priceResp =
                            pricingClient.getPriceById(tourDeparture.getTour().getTourPriceId());

                    response.setTourPrice(priceResp.getResult());

                    return response;
                })
                .collect(Collectors.toList());
    }

    public Map<Integer, List<TourDepartureResponse>> getDeparturesByTourIdsWithPrice(List<Integer> tourIds) {
        List<TourDeparture> allDepartures = tourDepartureRepository.findByTourIdIn(tourIds).stream()
                .filter(td -> td.getDepartureDate().isAfter(LocalDateTime.now()))
                .toList();

        List<Long> departurePriceIds = allDepartures.stream()
                .map(td -> td.getTour().getTourPriceId())
                .distinct()
                .toList();

        TourPriceBatchRequest depPriceBatchReq = new TourPriceBatchRequest();
        depPriceBatchReq.setIds(departurePriceIds);
        List<TourPriceResponse> depPriceList = pricingClient.getPricesBatch(depPriceBatchReq).getResult();

        Map<Long, TourPriceResponse> depPriceMap = depPriceList.stream()
                .collect(Collectors.toMap(TourPriceResponse::getId, p -> p));

        return allDepartures.stream()
                .map(td -> {
                    TourDepartureResponse tdResp = toResponse(td);
                    tdResp.setTourPrice(depPriceMap.get(td.getTour().getTourPriceId()));
                    return tdResp;
                })
                .collect(Collectors.groupingBy(TourDepartureResponse::getTourId));
    }



    public TourDepartureResponse create(TourDepartureRequest tourDepartureRequest) {
        Tour tour = tourRepository.findById(tourDepartureRequest.getTourId())
                .orElseThrow(() -> new AppException(ErrorCode.INVALID_KEY));

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("ddMMyy");
        String formattedDate = tourDepartureRequest.getDepartureDate().format(formatter);

        TourDeparture tourDeparture = TourDeparture.builder()
                // Vi du tourCode: NDSGN617-069-260925XE
                // Trong do NDSGN617 la tourProgram cua Tour. 069 la Tour Departure thu 69 cua Tour. 260925 la ngay khoi hanh. XE la id phuong tien khoi hanh

                .tourCode(tour.getTourProgram() + "-" +
                        String.format("%03d", countTourDeparturesOfTour(tour.getId()) + 1) + "-" +
                        formattedDate +
                        tour.getVehicle().getId())
                .departureDate(tourDepartureRequest.getDepartureDate())
                .returnDate((tourDepartureRequest.getReturnDate()))
                .availableSeats(tourDepartureRequest.getAvailableSeats())
                .tour(tour)
                .build();

        // Lấy toàn bộ departureDates của tour này
        List<String> departureDates = tourDepartureRepository.findByTourId(tour.getId())
                .stream()
                .map(td -> td.getDepartureDate().toString()) // có thể format lại nếu cần
                .collect(Collectors.toList());

        TourDeparture saved = tourDepartureRepository.save(tourDeparture);
        return toResponse(saved);
    }

    public TourDepartureResponse update(int id, TourDepartureRequest tourDepartureRequest) {
        TourDeparture tourDeparture = tourDepartureRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.INVALID_KEY));

        tourDeparture.setDepartureDate(tourDepartureRequest.getDepartureDate());
        tourDeparture.setReturnDate(tourDepartureRequest.getReturnDate());
        tourDeparture.setAvailableSeats(tourDepartureRequest.getAvailableSeats());

        TourDeparture saved = tourDepartureRepository.save(tourDeparture);

        return toResponse(saved);
    }

//    public void delete(int id) {
//        if (!tourDepartureRepository.existsById(id)) {
//            throw new AppException(ErrorCode.INVALID_KEY);
//        }
//
//        tourDepartureRepository.deleteById(id);
//    }


    //Ham dem so luong Tour Departure cua Tour
    public int countTourDeparturesOfTour(int tourId){
        return tourDepartureRepository.countByTourId(tourId);
    }

    private TourDepartureResponse toResponse(TourDeparture tourDeparture) {
        return TourDepartureResponse.builder()
                .id(tourDeparture.getId())
                .tourCode(tourDeparture.getTourCode())
                .departureDate(tourDeparture.getDepartureDate())
                .returnDate(tourDeparture.getReturnDate())
                .availableSeats(tourDeparture.getAvailableSeats())
                .tourId(tourDeparture.getTour().getId())
                .build();

    }


}
