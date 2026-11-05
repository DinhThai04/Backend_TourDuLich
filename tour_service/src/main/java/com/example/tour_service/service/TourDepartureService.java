package com.example.tour_service.service;

import com.example.tour_service.repository.httpClient.PricingClient;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TourDepartureService {
    /** The admin list endpoint is not paginated; keep the response bounded. */
    private static final int MAX_DEPARTURE_ROWS = 500;

    private final TourDepartureRepository tourDepartureRepository;
    private final TourRepository tourRepository;
    private final PricingClient pricingClient;
    private final DepartureCapacityReader capacityReader;
    private final org.springframework.context.ApplicationEventPublisher events;

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public com.example.tour_service.dto.response.DepartureCapacityResponse capacity(int id) {
        var departure = tourDepartureRepository.findById(id)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Không tìm thấy lịch khởi hành"));
        return capacityResponse(departure, capacityReader.read(departure, now()));
    }
    private TourDeparture locked(int id) {
        return tourDepartureRepository.lockById(id).orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"Không tìm thấy lịch khởi hành"));
    }
    @org.springframework.transaction.annotation.Transactional
    public void assertTourHasNoHistory(int tourId) {
        // Same departure locks as booking writers; retain schedule and change history together.
        var ids=tourDepartureRepository.findByTourId(tourId).stream().map(TourDeparture::getId).sorted().toList();
        for(int id:ids) {
            var departure=locked(id);
            if(capacityReader.read(departure,now()).relatedHistory())
                conflict("Tour có lịch sử booking hoặc dời lịch; không xóa dữ liệu lịch sử này");
        }
    }
    private static LocalDateTime now() { return LocalDateTime.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh")); }
    private com.example.tour_service.dto.response.DepartureCapacityResponse capacityResponse(TourDeparture d,DepartureCapacityReader.State state) {
        var issues=state.issues();
        boolean mismatch=issues.contains(DepartureCapacityReader.CAPACITY_MISMATCH);
        // The confirmed repair resolves only the mismatch; everything else keeps blocking the form.
        var blockingIssues=issues.stream()
                .filter(issue -> !DepartureCapacityReader.CAPACITY_MISMATCH.equals(issue))
                .toList();
        return new com.example.tour_service.dto.response.DepartureCapacityResponse(d.getId(),d.getTour().getId(),d.getTourCode(),d.getDepartureDate(),d.getReturnDate(),
                d.getCapacity(),d.getAvailableSeats(),d.getVersion(),state.paidSeats(),state.heldSeats(),state.overdueHolds(),state.historyCount(),
                false,d.getCapacity()==null || mismatch,issues,blockingIssues,state.warnings());
    }
    private static void conflict(String reason) { throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,reason); }
    private static void validateDates(LocalDateTime departure,LocalDateTime back) {
        if(departure==null || back==null || !departure.isAfter(now()) || !back.isAfter(departure))
            throw new IllegalArgumentException("Ngày đi phải ở tương lai và ngày về phải sau ngày đi");
    }
    private static int validCapacity(Integer capacity) {
        if(capacity==null || capacity<1 || capacity>32767) throw new IllegalArgumentException("Tổng sức chứa phải từ 1 đến 32767 người");
        return capacity;
    }

    public TourDepartureResponse getTourDepartureById(int id){
        TourDeparture tourDeparture = tourDepartureRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.TOUR_DEPARTURE_NOT_FOUND));
        // Ids are sequential, so the anonymous storefront must stay on the public calendar:
        // an ended, suspended or cancelled departure is only readable by a signed-in caller,
        // whose own booking history and the admin views legitimately need it.
        if (isAnonymousCaller() && !isPubliclyListable(tourDeparture))
            throw new AppException(ErrorCode.TOUR_DEPARTURE_NOT_FOUND);

        TourDepartureResponse response = toResponse(tourDeparture);
        // A pricing outage must not hide the departure itself.
        response.setTourPrice(PriceBatchLoader.oneOrNull(
                tourDeparture.getTour().getTourPriceId(), pricingClient::getPriceById));

        return response;
    }

    private boolean isPubliclyListable(TourDeparture departure) {
        return "OPEN".equals(departure.getOperationalStatus())
                && departure.getDepartureDate().isAfter(now());
    }

    private static boolean isAnonymousCaller() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        return auth == null || !auth.isAuthenticated()
                || auth instanceof org.springframework.security.authentication.AnonymousAuthenticationToken;
    }

    public List<TourDepartureResponse> getAllTourDeparture() {
        // Newest departures first; without an explicit order the capped page would be arbitrary.
        List<TourDeparture> allDepartures = tourDepartureRepository
                .findAll(PageRequest.of(0, MAX_DEPARTURE_ROWS,
                        org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "id")))
                .getContent();

        List<Long> departurePriceIds = allDepartures.stream()
                .map(td -> td.getTour().getTourPriceId())
                .distinct()
                .toList();

        List<TourPriceResponse> depPriceList = PriceBatchLoader.loadAvailable(departurePriceIds, pricingClient::getPricesBatch);

        Map<Long, TourPriceResponse> depPriceMap = depPriceList.stream()
                .collect(Collectors.toMap(TourPriceResponse::getId, p -> p, (first, ignored) -> first));

        return allDepartures.stream()
                .map(td -> {
                    TourDepartureResponse tdResp = toResponse(td);
                    tdResp.setTourPrice(depPriceMap.get(td.getTour().getTourPriceId()));
                    return tdResp;
                })
                .toList();
    }


    public List<TourDepartureResponse> getTourDepartureByTourId(int id){
        List<TourDeparture> departures = tourDepartureRepository.findOpenFutureByTourIds(List.of(id), now());
        List<Long> priceIds = departures.stream().map(td -> td.getTour().getTourPriceId()).distinct().toList();
        Map<Long, TourPriceResponse> prices = PriceBatchLoader.loadAvailable(priceIds, pricingClient::getPricesBatch).stream()
                .collect(Collectors.toMap(TourPriceResponse::getId, price -> price, (first, ignored) -> first));
        return departures.stream().map(tourDeparture -> {
            TourDepartureResponse response = toResponse(tourDeparture);
            response.setTourPrice(prices.get(tourDeparture.getTour().getTourPriceId()));
            return response;
        }).collect(Collectors.toList());
    }

    /** Admin screens need the full history, not just the storefront's open future departures. */
    public List<TourDepartureResponse> getTourDepartureHistory(int id) {
        List<TourDeparture> departures = tourDepartureRepository.findByTourId(id,
                org.springframework.data.domain.PageRequest.of(0, MAX_DEPARTURE_ROWS,
                        org.springframework.data.domain.Sort.by(
                                org.springframework.data.domain.Sort.Direction.DESC, "departureDate")));
        List<Long> priceIds = departures.stream().map(td -> td.getTour().getTourPriceId()).distinct().toList();
        Map<Long, TourPriceResponse> prices = PriceBatchLoader.loadAvailable(priceIds, pricingClient::getPricesBatch).stream()
                .collect(Collectors.toMap(TourPriceResponse::getId, price -> price, (first, ignored) -> first));
        return departures.stream().map(tourDeparture -> {
            TourDepartureResponse response = toResponse(tourDeparture);
            response.setTourPrice(prices.get(tourDeparture.getTour().getTourPriceId()));
            return response;
        }).toList();
    }

    public Map<Integer, List<TourDepartureResponse>> getDeparturesByTourIdsWithPrice(List<Integer> tourIds) {
        if (tourIds == null || tourIds.isEmpty()) return Map.of();
        List<TourDeparture> allDepartures = tourDepartureRepository.findOpenFutureByTourIds(tourIds, now());

        List<Long> departurePriceIds = allDepartures.stream()
                .map(td -> td.getTour().getTourPriceId())
                .distinct()
                .toList();

        List<TourPriceResponse> depPriceList = PriceBatchLoader.loadAvailable(departurePriceIds, pricingClient::getPricesBatch);

        Map<Long, TourPriceResponse> depPriceMap = depPriceList.stream()
                .collect(Collectors.toMap(TourPriceResponse::getId, p -> p, (first, ignored) -> first));

        return allDepartures.stream()
                .map(td -> {
                    TourDepartureResponse tdResp = toResponse(td);
                    tdResp.setTourPrice(depPriceMap.get(td.getTour().getTourPriceId()));
                    return tdResp;
                })
                .collect(Collectors.groupingBy(TourDepartureResponse::getTourId));
    }



    @org.springframework.transaction.annotation.Transactional
    public TourDepartureResponse create(TourDepartureRequest tourDepartureRequest) {
        validateDates(tourDepartureRequest.getDepartureDate(),tourDepartureRequest.getReturnDate());
        int total=validCapacity(tourDepartureRequest.getCapacity());
        if(tourDepartureRequest.getAvailableSeats()!=null) throw new IllegalArgumentException("Nhập tổng sức chứa, không gửi số chỗ còn lại");
        Tour tour = tourRepository.lockForItinerary(tourDepartureRequest.getTourId())
                .orElseThrow(() -> new AppException(ErrorCode.TOUR_NOT_FOUND));
        // The tour row is locked, so two concurrent creates cannot both pass this check.
        if(tourDepartureRepository.existsByTourIdAndDepartureDate(tour.getId(), tourDepartureRequest.getDepartureDate()))
            conflict("Tour đã có lịch khởi hành vào thời điểm này");

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
                .availableSeats((short)total)
                .capacity(total)
                .tour(tour)
                .build();

        TourDeparture saved = tourDepartureRepository.save(tourDeparture);
        // The storefront widget caches tours with their next departure; refresh it after commit.
        AfterCommit.run(() -> events.publishEvent(new TourDeparturesChangedEvent()),
                "Cannot evict the random tour cache after creating departure " + saved.getId());
        return toResponse(saved);
    }

    @org.springframework.transaction.annotation.Transactional
    public TourDepartureResponse update(int id, TourDepartureRequest request) {
        if(request.getAvailableSeats()!=null) throw new IllegalArgumentException("Không cập nhật trực tiếp số chỗ còn lại; hãy tải form sức chứa mới");
        int total=validCapacity(request.getCapacity());
        var departure=locked(id);
        if(request.getExpectedVersion()==null || request.getExpectedVersion()!=departure.getVersion())
            conflict("Dữ liệu chuyến đi đã thay đổi. Vui lòng tải lại trước khi lưu");
        if(!departure.getDepartureDate().isAfter(now())) conflict("Chuyến đã khởi hành; giữ lịch cũ để tra cứu");
        if(request.getTourId()!=0 && request.getTourId()!=departure.getTour().getId()) conflict("Không chuyển lịch khởi hành sang tour khác");
        var state=capacityReader.read(departure,now());
        // A confirmed repair may fix the capacity/available-seats mismatch itself; every other
        // inconsistency still blocks the update until it is investigated.
        var blocking=state.issues().stream()
                .filter(issue -> !(request.isConfirmCapacity() && DepartureCapacityReader.CAPACITY_MISMATCH.equals(issue)))
                .toList();
        if(!blocking.isEmpty()) conflict(String.join("; ",blocking));
        if(departure.getCapacity()==null && !request.isConfirmCapacity())
            conflict("Cần xác nhận tổng sức chứa thực tế cho lịch cũ trước khi lưu");
        var nextDate=request.getDepartureDate()==null?departure.getDepartureDate():request.getDepartureDate();
        var nextReturn=request.getReturnDate()==null?departure.getReturnDate():request.getReturnDate();
        boolean datesChanged=!nextDate.equals(departure.getDepartureDate()) || !nextReturn.equals(departure.getReturnDate());
        if(datesChanged) conflict("Không đổi ngày/giờ trực tiếp. Chỉ dời chuyến bằng quy trình sự cố bắt buộc và xác nhận của khách");
        if(!"OPEN".equals(departure.getOperationalStatus())) conflict("Lịch đang có sự cố; xử lý tại mục dời lịch");
        validateDates(nextDate,nextReturn);
        if(total<state.occupied()) conflict("Tổng sức chứa không thể nhỏ hơn "+state.occupied()+" chỗ đã thanh toán/đang giữ");
        departure.setDepartureDate(nextDate);departure.setReturnDate(nextReturn);
        departure.setCapacity(total);departure.setAvailableSeats((short)(total-state.occupied()));
        TourDeparture saved = tourDepartureRepository.saveAndFlush(departure);
        // Capacity or seat edits change the widget's "next departure" block.
        AfterCommit.run(() -> events.publishEvent(new TourDeparturesChangedEvent()),
                "Cannot evict the random tour cache after updating departure " + saved.getId());
        return toResponse(saved);
    }

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
                .capacity(tourDeparture.getCapacity())
                .operationalStatus(tourDeparture.getOperationalStatus())
                .version(tourDeparture.getVersion())
                .tourId(tourDeparture.getTour().getId())
                .build();

    }


}
