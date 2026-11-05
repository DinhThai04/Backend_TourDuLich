package com.example.tour_service.service;

import com.example.tour_service.dto.request.TourPriceBatchRequest;
import com.example.tour_service.dto.request.UpdateTourRequest;
import com.example.tour_service.dto.response.*;
import com.example.tour_service.repository.TourSpecification;
import com.example.tour_service.repository.httpClient.PricingClient;
import com.example.tour_service.dto.request.TourRequest;
import com.example.tour_service.dto.request.TourSearchRequest;
import com.example.tour_service.entity.Location;
import com.example.tour_service.entity.Tour;
import com.example.tour_service.entity.Vehicle;
import com.example.tour_service.exception.AppException;
import com.example.tour_service.exception.ErrorCode;
import com.example.tour_service.repository.ItineraryRepository;
import com.example.tour_service.repository.LocationRepository;
import com.example.tour_service.repository.TourRepository;
import com.example.tour_service.repository.VehicleRepository;
import com.example.tour_service.repository.httpClient.FileClient;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TourService {

        /** The storefront list endpoint is not paginated, so it must stay bounded. */
        private static final int MAX_CATALOGUE_SIZE = 200;

        /** One spare row so "other tours" can drop the current tour and still show three. */
        private static final int RANDOM_POOL_SIZE = 4;

        /** Homepage widget: 0 keeps a fresh pick per view; a positive TTL trades variety for fewer queries. */
        @org.springframework.beans.factory.annotation.Value("${app.tour-cache.random-ttl-ms:0}")
        private long randomToursTtlMs = 0;

        /** Same property the pageable resolver reads, so its clamp and the search guard cannot drift apart. */
        @org.springframework.beans.factory.annotation.Value("${spring.data.web.pageable.max-page-size:50}")
        private int maxPageSize = 50;

        private final AtomicReference<CachedRandomTours> randomTours = new AtomicReference<>();

        private final TourRepository tourRepository;
        private final ItineraryRepository itineraryRepository;
        private final LocationRepository locationRepository;
        private final VehicleRepository vehicleRepository;
        private final PricingClient pricingClient;
        private final FileClient fileClient;
        private final TourImageCache tourImageCache;
        private final TourDepartureService tourDepartureService;
        private final TransactionTemplate transactions;
        private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public TourResponse getTourById(int id) {
        Tour tour = tourRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.TOUR_NOT_FOUND));

        TourResponse response = toResponse(tour);
        response.setImageIds(loadImages().getOrDefault(String.valueOf(id), List.of()));
        return response;
    }

    public List<TourResponse> getAllTours() {
        return enrichList(tourRepository.findAll(
                PageRequest.of(0, MAX_CATALOGUE_SIZE, Sort.by(Sort.Direction.DESC, "id"))).getContent());
    }

    /**
     * Loads grouped image URLs from the cache, so tour reads never depend on file-service uptime.
     */
    private Map<String, List<String>> loadImages() {
        return tourImageCache.byTourId();
    }

    

    public TourResponse createTour(TourRequest request) {
            ImageUploadPolicy.validate(request.getFiles());
            Location departure = locationRepository.findById(request.getDepartureLocationId())
                            .orElseThrow(() -> new AppException(ErrorCode.LOCATION_NOT_FOUND));
            Location destination = locationRepository.findById(request.getDestinationLocationId())
                            .orElseThrow(() -> new AppException(ErrorCode.LOCATION_NOT_FOUND));
            Vehicle vehicle = vehicleRepository.findById(request.getVehicleId())
                            .orElseThrow(() -> new AppException(ErrorCode.VEHICLE_NOT_FOUND));

            TourPriceResponse selectedPrice = requirePrice(request.getTourPriceId());
            Tour tour = Tour.builder()
                            .tourProgram(request.getTourProgram())
                            .tourTitle(request.getTourTitle())
                            .description(request.getDescription())
                            .duration(request.getDuration())
                            .basePrice(selectedPrice.getAdultPrice())
                            .departureLocation(departure)
                            .destinationLocation(destination)
                            .vehicle(vehicle)
                            .tourPriceId(request.getTourPriceId())
                            .build();


            // Resolve pricing/departure dependencies before persisting, so their failure cannot hide a newly created tour ID.
            TourResponse response = withPricing(baseResponse(tour), selectedPrice, List.of());
            Tour saved = tourRepository.save(tour);
            response.setId(saved.getId());
            response.setImageIds(List.of());
            response.setImageUploadStatus("COMPLETE");
            if (request.getFiles() != null && !request.getFiles().isEmpty()) {
                    try {
                            var images = fileClient.uploadMultipleFiles(
                                            String.valueOf(saved.getId()),
                                            request.getFiles());
                            List<String> urls = images.stream().map(FileResponse::url).toList();
                            tourImageCache.update(String.valueOf(saved.getId()), urls);
                            response.setImageIds(urls);
                    } catch (Exception e) {
                            response.setImageUploadStatus("PENDING");
                            response.setImageUploadMessage("Tour đã được tạo nhưng ảnh chưa hoàn tất. Hãy thử tải lại ảnh cho tour này");
                    }
            }
            evictRandomTours();
            return response;
    }

    public TourResponse retryTourImages(int id, List<org.springframework.web.multipart.MultipartFile> files) {
        ImageUploadPolicy.validate(files);
        if (files == null || files.isEmpty()) throw new IllegalArgumentException("Vui lòng chọn ảnh để thử lại");
        var tour = tourRepository.findById(id).orElseThrow(() -> new AppException(ErrorCode.TOUR_NOT_FOUND));
        var response = toResponse(tour);
        var images = fileClient.uploadMultipleFiles(String.valueOf(id), files);
        List<String> urls = images.stream().map(FileResponse::url).toList();
        tourImageCache.update(String.valueOf(id), urls);
        response.setImageIds(urls);
        response.setImageUploadStatus("COMPLETE");
        evictRandomTours();
        return response;
    }

    public TourResponse updateTour(int id, UpdateTourRequest request) {
        ImageUploadPolicy.validate(request.getFiles());
        if (!tourRepository.existsById(id)) {
            throw new AppException(ErrorCode.TOUR_NOT_FOUND);
        }

        // Validate every business rule before touching file-service, so a rejected update
        // never leaves a half-applied gallery change.
        Location departureLocation = locationRepository.findById(request.getDepartureLocationId())
                .orElseThrow(() -> new AppException(ErrorCode.LOCATION_NOT_FOUND));
        Location destinationLocation = locationRepository.findById(request.getDestinationLocationId())
                .orElseThrow(() -> new AppException(ErrorCode.LOCATION_NOT_FOUND));
        Vehicle vehicle = vehicleRepository.findById(request.getVehicleId())
                .orElseThrow(() -> new AppException(ErrorCode.VEHICLE_NOT_FOUND));
        requireDurationCoversItinerary(id, request.getDuration());

        // Remote price/image calls run before the transaction: a slow HTTP call must not hold row locks.
        TourPriceResponse selectedPrice = requirePrice(request.getTourPriceId());
        List<String> uploadedImages = editImages(id, request);

        Tour saved = transactions.execute(tx -> {
            Tour existingTour = tourRepository.lockForItinerary(id)
                    .orElseThrow(() -> new AppException(ErrorCode.TOUR_NOT_FOUND));
            // Itinerary rows are appended under the same tour lock, so the duration must be re-checked here.
            requireDurationCoversItinerary(id, request.getDuration());
            if (uploadedImages != null) {
                // file-service already applied the gallery; if this transaction rolls back the cache
                // must follow the remote truth, or the next edit starts from a stale gallery and
                // is rejected as "images changed".
                List<String> applied = uploadedImages;
                AfterCommit.onRollback(
                        () -> tourImageCache.update(String.valueOf(id), applied),
                        "Tour " + id + " gallery was applied by file-service but the update rolled back;"
                                + " cache resync failed");
            }
            // Persist the compatibility snapshot; public prices and filters use the adult fare.
            existingTour.setBasePrice(selectedPrice.getAdultPrice());
            existingTour.setTourProgram(request.getTourProgram());
            existingTour.setTourTitle(request.getTourTitle());
            existingTour.setDescription(request.getDescription());
            existingTour.setDuration(request.getDuration());
            existingTour.setDepartureLocation(departureLocation);
            existingTour.setDestinationLocation(destinationLocation);
            existingTour.setVehicle(vehicle);
            existingTour.setTourPriceId(request.getTourPriceId());
            return tourRepository.save(existingTour);
        });

        TourResponse response = withPricing(baseResponse(saved), selectedPrice,
                tourDepartureService.getTourDepartureByTourId(id));
        if (uploadedImages != null) {
            // Refresh the cache only after the DB commit, so readers never see images of a failed update.
            AfterCommit.run(() -> tourImageCache.update(String.valueOf(id), uploadedImages),
                    "Cannot cache the new gallery of tour " + id);
            response.setImageIds(uploadedImages);
        } else {
            response.setImageIds(loadImages().getOrDefault(String.valueOf(id), List.of()));
        }
        AfterCommit.run(this::evictRandomTours, "Cannot evict the random tour cache");
        return response;
    }

    /**
     * Applies the gallery change against file-service before the DB transaction.
     *
     * @return the resulting image URLs, or {@code null} when the request keeps the current images
     */
    private List<String> editImages(int id, UpdateTourRequest request) {
        boolean changesImages = request.getFiles() != null && !request.getFiles().isEmpty()
                || request.getUrl() != null && !request.getUrl().isEmpty();
        if (!changesImages) return null;

        String changes;
        try {
            changes = new ObjectMapper().writeValueAsString(
                    request.getUrl() == null ? List.of() : request.getUrl());
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("Danh sách ảnh không hợp lệ", invalid);
        }
        return fileClient.editTourImages(String.valueOf(id), changes,
                        request.getFiles() == null || request.getFiles().isEmpty() ? null : request.getFiles())
                .stream()
                .filter(image -> String.valueOf(id).equals(image.getTourId()))
                .map(TourFileResponse::getUrl)
                .filter(Objects::nonNull)
                .toList();
    }

    private void requireDurationCoversItinerary(int tourId, int duration) {
        var lastDay = itineraryRepository.findTopByTourIdOrderByDayNumberDesc(tourId);
        if (lastDay != null && duration < lastDay.getDayNumber()) {
            throw new IllegalArgumentException("Thời lượng tour phải ít nhất " + lastDay.getDayNumber()
                    + " ngày để chứa lịch trình hiện có");
        }
    }

    public void deleteTour(int id) {
        // A missing tour must be a 404 before any remote call: the file-service is queried with this
        // id, and its outage must not turn a nonexistent tour into a 503.
        if (!tourRepository.existsById(id)) throw new AppException(ErrorCode.TOUR_NOT_FOUND);
        // Read the gallery before opening the transaction: an unreadable file-service must abort
        // the deletion without holding a DB connection (and the tour row lock) while we wait on HTTP.
        List<String> gallery = galleryOf(id);
        transactions.executeWithoutResult(status -> {
            Tour tour = tourRepository.lockForItinerary(id)
                    .orElseThrow(() -> new AppException(ErrorCode.TOUR_NOT_FOUND));

            tourDepartureService.assertTourHasNoHistory(id);
            itineraryRepository.deleteByTourId(id);
            // Legacy join table has no cascade and no entity mapping, so it must be cleared explicitly.
            jdbc.update("DELETE FROM tour_image_ids WHERE tour_id=?", id);
            tourRepository.delete(tour);
            if (!gallery.isEmpty()) {
                AfterCommit.run(() -> fileClient.editTourImages(String.valueOf(id), json(gallery), null),
                        "Tour " + id + " was deleted but its gallery could not be detached;"
                                + " clean up its images in file-service");
            }
            // The rows disappear only at commit; clearing the cache earlier would hide a still-existing tour.
            AfterCommit.run(() -> tourImageCache.update(String.valueOf(id), List.of()),
                    "Cannot clear cached images of deleted tour " + id);
            AfterCommit.run(this::evictRandomTours, "Cannot evict the random tour cache");
        });
    }

    private List<String> galleryOf(int id) {
        try {
            return fileClient.getTourMedia(List.of(String.valueOf(id))).stream()
                    .map(TourFileResponse::getUrl)
                    .filter(Objects::nonNull)
                    .toList();
        } catch (RuntimeException unavailable) {
            // Deleting without the gallery would leave rows pointing at a tour that no longer
            // exists, and those rows are not part of the detached-image cleanup list.
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "Không đọc được ảnh của tour; tour chưa bị xóa. Vui lòng thử lại.", unavailable);
        }
    }

    private static String json(List<String> urls) {
        try {
            return new ObjectMapper().writeValueAsString(urls);
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("Danh sách ảnh không hợp lệ", invalid);
        }
    }

    public Page<TourResponse> searchTours(TourSearchRequest request, Pageable pageable) {
        // Public endpoint: bound the page/size so a deep offset cannot exhaust the DB pool.
        if (pageable.getPageNumber() < 0 || pageable.getPageNumber() > 2_000
                || pageable.getPageSize() < 1 || pageable.getPageSize() > maxPageSize)
            throw new IllegalArgumentException(
                    "Trang phải từ 0 đến 2000; mỗi trang từ 1 đến " + maxPageSize + " tour");
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        Specification<Tour> spec = TourSpecification.build(request, now);
        Page<Tour> toursPage;
        if ("dateSoon".equals(request.getSort())) {
            toursPage = tourRepository.findByEarliestDeparture(spec, pageable, now, request.getDepD());
        } else {
            // 2. TẠO LOGIC SORT TỪ REQUEST (Giống như chúng ta đã làm)
            String sortField = "createdAt"; // Mặc định
            Sort.Direction direction = Sort.Direction.DESC; // Mặc định

            String sortRequest = (request.getSort() != null && !request.getSort().isEmpty())
                    ? request.getSort()
                    : "newest";

            switch (sortRequest) {
                case "priceLow":
                    sortField = "priceView.adultPrice";
                    direction = Sort.Direction.ASC;
                    break;
                case "priceHigh":
                    sortField = "priceView.adultPrice";
                    direction = Sort.Direction.DESC;
                    break;
                case "newest":
                default:
                    sortField = "id"; // Hoặc "createAt"
                    direction = Sort.Direction.DESC;
                    break;
            }
            // Tours share prices constantly, so price sorts need a second key: without it MySQL may
            // reorder equal rows between requests and one tour repeats or disappears while paging.
            Sort sort = "id".equals(sortField)
                    ? Sort.by(direction, sortField)
                    : Sort.by(direction, sortField).and(Sort.by(Sort.Direction.ASC, "id"));

            // 3. TẠO PAGEABLE MỚI KẾT HỢP LOGIC CŨ VÀ SORT MỚI
            Pageable newPageable = PageRequest.of(
                    pageable.getPageNumber(),
                    pageable.getPageSize(),
                    sort
            );

            toursPage = tourRepository.findAll(spec, newPageable);
        }

        // Batch remote lookups instead of two calls per tour in the page.
        return enrich(toursPage);
    }

    private TourPriceResponse requirePrice(Long id) {
        if (id == null) throw new IllegalArgumentException("Vui lòng chọn bảng giá");
        var response = pricingClient.getPriceById(id);
        if (response == null || response.getResult() == null || response.getResult().getAdultPrice() == null
                || response.getResult().getAdultPrice().signum() <= 0)
            throw new IllegalArgumentException("Bảng giá người lớn không hợp lệ");
        return response.getResult();
    }

    private TourResponse toResponse(Tour tour) {
        // Detail pages fall back to the stored base price when pricing is unavailable.
        TourPriceResponse price = PriceBatchLoader.oneOrNull(tour.getTourPriceId(), pricingClient::getPriceById);
        List<TourDepartureResponse> departures = tourDepartureService.getTourDepartureByTourId(tour.getId());
        return withPricing(baseResponse(tour), price, departures);
    }

    private TourResponse baseResponse(Tour tour) {
        return TourResponse.builder()
                .id(tour.getId())
                .tourProgram(tour.getTourProgram())
                .tourTitle(tour.getTourTitle())
                .description(tour.getDescription())
                .duration(tour.getDuration())
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

    private TourResponse withPricing(TourResponse response, TourPriceResponse price,
            List<TourDepartureResponse> departures) {
        response.setTourPrice(price);
        response.setDepartures(departures);
        if (price != null && price.getAdultPrice() != null) {
            response.setBasePrice(price.getAdultPrice());
        } else {
            // Only the degraded path keeps the stored snapshot, so callers can flag the price as stale.
            response.setPriceStale(true);
        }
        return response;
    }

    private Page<TourResponse> enrich(Page<Tour> page) {
        List<Tour> tours = page.getContent();
        if (tours.isEmpty()) return page.map(this::baseResponse);
        Map<Long, TourPriceResponse> priceMap = loadPrices(tours);
        Map<Integer, List<TourDepartureResponse>> departuresMap = loadDepartures(tours);
        Map<String, List<String>> imagesMap = loadImages();
        return page.map(tour -> {
            TourResponse response = withPricing(baseResponse(tour), priceMap.get(tour.getTourPriceId()),
                    departuresMap.getOrDefault(tour.getId(), List.of()));
            response.setImageIds(imagesMap.getOrDefault(String.valueOf(tour.getId()), List.of()));
            return response;
        });
    }

    private Map<Long, TourPriceResponse> loadPrices(List<Tour> tours) {
        List<Long> priceIds = tours.stream()
                .map(Tour::getTourPriceId)
                .filter(id -> id != null)
                .distinct()
                .toList();
        return PriceBatchLoader.loadAvailable(priceIds, pricingClient::getPricesBatch)
                .stream()
                .collect(Collectors.toMap(TourPriceResponse::getId, price -> price, (first, ignored) -> first));
    }

    private Map<Integer, List<TourDepartureResponse>> loadDepartures(List<Tour> tours) {
        List<Integer> tourIds = tours.stream().map(Tour::getId).toList();
        return tourDepartureService.getDeparturesByTourIdsWithPrice(tourIds);
    }

    public List<TourResponse> getRandom3Tours(Integer excludeId) {
        CachedRandomTours cached = randomTours.get();
        List<TourResponse> tours;
        if (cached != null && cached.expiresAt() > System.currentTimeMillis()) {
            tours = cached.tours();
        } else {
            // Fetch one extra row so excluding the page's own tour still leaves three picks.
            tours = List.copyOf(enrichList(tourRepository.findRandom3(PageRequest.of(0, RANDOM_POOL_SIZE))));
            if (randomToursTtlMs > 0) {
                randomTours.set(new CachedRandomTours(tours, System.currentTimeMillis() + randomToursTtlMs));
            }
        }
        return tours.stream()
                .filter(tour -> excludeId == null || !excludeId.equals(tour.getId()))
                .limit(3)
                .toList();
    }

    /** Mutations drop the widget cache so a new or edited tour never hides behind a stale pick. */
    private void evictRandomTours() {
        randomTours.set(null);
    }

    /** A departure added or resized changes the "next departure" block shown on the widget. */
    @org.springframework.context.event.EventListener
    public void onDeparturesChanged(TourDeparturesChangedEvent event) {
        evictRandomTours();
    }

    private List<TourResponse> enrichList(List<Tour> tours) {
        if (tours.isEmpty()) return List.of();
        Map<Long, TourPriceResponse> priceMap = loadPrices(tours);
        Map<Integer, List<TourDepartureResponse>> departuresMap = loadDepartures(tours);
        Map<String, List<String>> imagesMap = loadImages();
        return tours.stream()
                .map(tour -> {
                    TourResponse response = withPricing(baseResponse(tour), priceMap.get(tour.getTourPriceId()),
                            departuresMap.getOrDefault(tour.getId(), List.of()));
                    response.setImageIds(imagesMap.getOrDefault(String.valueOf(tour.getId()), List.of()));
                    return response;
                })
                .collect(Collectors.toList());
    }

    private record CachedRandomTours(List<TourResponse> tours, long expiresAt) {}

}
