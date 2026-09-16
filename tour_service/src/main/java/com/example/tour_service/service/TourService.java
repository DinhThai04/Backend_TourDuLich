package com.example.tour_service.service;

import com.example.tour_service.dto.request.TourPriceBatchRequest;
import com.example.tour_service.dto.request.UpdateTourRequest;
import com.example.tour_service.dto.response.*;
import com.example.tour_service.repository.TourSpecification;
import com.example.tour_service.repository.httpClient.PricingClient;
import com.example.tour_service.dto.request.ApiResponse;
import com.example.tour_service.dto.request.TourRequest;
import com.example.tour_service.dto.request.TourSearchRequest;
import com.example.tour_service.entity.Location;
import com.example.tour_service.entity.Tour;
import com.example.tour_service.entity.Vehicle;
import com.example.tour_service.exception.AppException;
import com.example.tour_service.exception.ErrorCode;
import com.example.tour_service.repository.LocationRepository;
import com.example.tour_service.repository.TourRepository;
import com.example.tour_service.repository.VehicleRepository;
import com.example.tour_service.repository.httpClient.FileClient;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TourService {

        private final TourRepository tourRepository;
        private final LocationRepository locationRepository;
        private final VehicleRepository vehicleRepository;
        private final PricingClient pricingClient;
        private final FileClient fileClient;
        private final TourDepartureService tourDepartureService;

    public TourResponse getTourById(int id) {
        Tour tour = tourRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.INVALID_KEY));

        TourResponse response = toResponse(tour);

        ApiResponse<TourPriceResponse> priceResp = pricingClient.getPriceById(tour.getTourPriceId());
        response.setTourPrice(priceResp.getResult());

        List<TourDepartureResponse> departures = tourDepartureService.getTourDepartureByTourId(id);
        response.setDepartures(departures);

        List<TourFileResponse> fileResponses = fileClient.getAllMedia();
        List<String> tourImages = fileResponses.stream()
                .filter(f -> String.valueOf(id).equals(f.getTourId()))
                .filter(f -> f.getUrl() != null && !f.getUrl().isEmpty())
                .map(TourFileResponse::getUrl)
                .collect(Collectors.toList());

        response.setImageIds(tourImages);

        return response;
    }

    public List<TourResponse> getAllTours() {
            List<TourFileResponse> fileResponses = fileClient.getAllMedia();
            Map<String, List<String>> tourImagesMap = fileResponses.stream()
                            .filter(f -> f.getUrl() != null && !f.getUrl().isEmpty())
                            .collect(Collectors.groupingBy(
                                            TourFileResponse::getTourId,
                                            Collectors.mapping(TourFileResponse::getUrl, Collectors.toList())));
            return tourRepository.findAll().stream()
                            .map(tour -> {
                                    TourResponse response = toResponse(tour);
                                    ApiResponse<TourPriceResponse> priceResp = pricingClient
                                                    .getPriceById(tour.getTourPriceId());
                                    List<TourDepartureResponse> departures = tourDepartureService.getTourDepartureByTourId(tour.getId());
                                    response.setDepartures(departures);
                                    response.setTourPrice(priceResp.getResult());
                                    List<String> urls = tourImagesMap.get(String.valueOf(tour.getId()));
                                    response.setImageIds(urls != null ? urls : List.of());
                                    return response;
                            })
                            .collect(Collectors.toList());
    }

    

    public TourResponse createTour(TourRequest request) {
            Location departure = locationRepository.findById(request.getDepartureLocationId())
                            .orElseThrow(() -> new AppException(ErrorCode.INVALID_KEY));
            Location destination = locationRepository.findById(request.getDestinationLocationId())
                            .orElseThrow(() -> new AppException(ErrorCode.INVALID_KEY));
            Vehicle vehicle = vehicleRepository.findById(request.getVehicleId())
                            .orElseThrow(() -> new AppException(ErrorCode.INVALID_KEY));

            Tour tour = Tour.builder()
                            .tourProgram(request.getTourProgram())
                            .tourTitle(request.getTourTitle())
                            .description(request.getDescription())
                            .duration(request.getDuration())
                            .basePrice(request.getBasePrice())
                            .departureLocation(departure)
                            .destinationLocation(destination)
                            .vehicle(vehicle)
                            .tourPriceId(request.getTourPriceId())
                            .build();


            Tour saved = tourRepository.save(tour);

            if (request.getFiles() != null && !request.getFiles().isEmpty()) {
                    try {
                            fileClient.uploadMultipleFiles(
                                            String.valueOf(saved.getId()),
                                            request.getFiles());
                    } catch (Exception e) {
                            e.printStackTrace();
                    }
            }

            return toResponse(saved);
    }

    public TourResponse updateTour(int id, TourRequest request) {
        Tour existingTour = tourRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.INVALID_KEY));

        Location existingDepartureLocation = locationRepository.findById(request.getDepartureLocationId())
                .orElseThrow(() -> new AppException(ErrorCode.INVALID_KEY));

        Location existingDestinationLocation = locationRepository.findById(request.getDestinationLocationId())
                .orElseThrow(() -> new AppException(ErrorCode.INVALID_KEY));


        Vehicle existingVehicle = vehicleRepository.findById(request.getVehicleId())
                .orElseThrow(() -> new AppException(ErrorCode.INVALID_KEY));


        //Cap nhat Tour
        existingTour.setTourTitle(request.getTourTitle());
        existingTour.setDescription(request.getDescription());
        existingTour.setDuration(request.getDuration());
        existingTour.setDepartureLocation(existingDepartureLocation);
        existingTour.setDestinationLocation(existingDestinationLocation);
        existingTour.setVehicle(existingVehicle);
        existingTour.setTourPriceId(request.getTourPriceId());

        Tour saved = tourRepository.save(existingTour);
        return toResponse(saved);
    }

    public void deleteTour(int id) {
        if (!tourRepository.existsById(id)) {
            throw new AppException(ErrorCode.INVALID_KEY);
        }

        tourRepository.deleteById(id);
    }

    public Page<TourResponse> searchTours(TourSearchRequest request, Pageable pageable) {
        // 1. Tạo specification từ request
        Specification<Tour> spec = TourSpecification.build(request);

        // 2. TẠO LOGIC SORT TỪ REQUEST (Giống như chúng ta đã làm)
        String sortField = "createdAt"; // Mặc định
        Sort.Direction direction = Sort.Direction.DESC; // Mặc định

        String sortRequest = (request.getSort() != null && !request.getSort().isEmpty())
                ? request.getSort()
                : "newest";

        switch (sortRequest) {
            case "dateSoon":
                sortField = "departureDate";
                direction = Sort.Direction.ASC;
                break;
            case "priceLow":
                sortField = "basePrice";
                direction = Sort.Direction.ASC;
                break;
            case "priceHigh":
                sortField = "basePrice";
                direction = Sort.Direction.DESC;
                break;
            case "newest":
            default:
                sortField = "id"; // Hoặc "createAt"
                direction = Sort.Direction.DESC;
                break;
        }
        Sort sort = Sort.by(direction, sortField);

        // 3. TẠO PAGEABLE MỚI KẾT HỢP LOGIC CŨ VÀ SORT MỚI
        // Chúng ta lấy số trang và kích thước trang từ 'pageable' cũ,
        // nhưng áp dụng 'sort' mới mà chúng ta vừa tạo.
        Pageable newPageable = PageRequest.of(
                pageable.getPageNumber(),
                pageable.getPageSize(),
                sort
        );

        // 2. Gọi repository với cả spec và pageable
        Page<Tour> toursPage = tourRepository.findAll(spec, newPageable);

        // 3. Chuyển đổi Page<Tour> sang Page<TourResponse>
        return toursPage.map(this::toResponse);
    }

    private TourResponse toResponse(Tour tour) {
        ApiResponse<TourPriceResponse> priceResp = pricingClient
                .getPriceById(tour.getTourPriceId());
        List<TourDepartureResponse> departures = tourDepartureService.getTourDepartureByTourId(tour.getId());

        return TourResponse.builder()
                .id(tour.getId())
                .tourProgram(tour.getTourProgram())
                .tourTitle(tour.getTourTitle())
                .description(tour.getDescription())
                .duration(tour.getDuration())
                .departures(departures)
                .tourPrice(priceResp.getResult())
                .departureCity(
                        LocationResponse.builder()
                                .id(tour.getDepartureLocation().getId())
                                .city(tour.getDepartureLocation().getCity())
                                .type(tour.getDepartureLocation().getType())
                                .build()
                )
                .destinationCity(
                        LocationResponse.builder()
                                .id(tour.getDestinationLocation().getId())
                                .city(tour.getDestinationLocation().getCity())
                                .type(tour.getDestinationLocation().getType())
                                .build()
                )
                .basePrice(tour.getBasePrice())
                .vehicle(
                        VehicleResponse.builder()
                                .id(tour.getVehicle().getId())
                                .name(tour.getVehicle().getVehicleType())
                                .build()
                )
                .build();
    }

    public List<TourResponse> getRandom3Tours() {

            // Lấy toàn bộ tour trong DB
            List<Tour> tours = tourRepository.findAll();

            if (tours.isEmpty())
                    return List.of();

            // Shuffle để ngẫu nhiên
            Collections.shuffle(tours);

            // Chỉ lấy 3 tour đầu tiên
            List<Tour> selectedTours = tours.stream()
                            .limit(3)
                            .toList();

            // BATCH PRICE
            List<Long> priceIds = selectedTours.stream()
                            .map(Tour::getTourPriceId)
                            .distinct()
                            .toList();

            TourPriceBatchRequest priceBatchReq = new TourPriceBatchRequest();
            priceBatchReq.setIds(priceIds);

            Map<Long, TourPriceResponse> priceMap = pricingClient
                            .getPricesBatch(priceBatchReq)
                            .getResult()
                            .stream()
                            .collect(Collectors.toMap(TourPriceResponse::getId, p -> p));

            // BATCH DEPARTURES
            List<Integer> tourIds = selectedTours.stream()
                            .map(Tour::getId)
                            .toList();

            Map<Integer, List<TourDepartureResponse>> departuresMap = tourDepartureService
                            .getDeparturesByTourIdsWithPrice(tourIds);

            List<TourFileResponse> fileResponses = fileClient.getAllMedia();
            Map<String, List<String>> tourImagesMap = fileResponses.stream()
                            .filter(f -> f.getUrl() != null && !f.getUrl().isEmpty())
                            .collect(Collectors.groupingBy(
                                            TourFileResponse::getTourId,
                                            Collectors.mapping(TourFileResponse::getUrl, Collectors.toList())));

            return selectedTours.stream()
                            .map(tour -> {
                                    TourResponse response = toResponse(tour);

                                    response.setTourPrice(priceMap.get(tour.getTourPriceId()));

                                    List<String> urls = tourImagesMap.get(String.valueOf(tour.getId()));
                                    response.setImageIds(urls != null ? urls : List.of());

                                    response.setDepartures(departuresMap.getOrDefault(tour.getId(), List.of()));

                                    return response;
                            })
                            .toList();
    }

}
