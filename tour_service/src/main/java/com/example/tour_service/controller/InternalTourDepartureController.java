package com.example.tour_service.controller;

import com.example.tour_service.dto.internal.HoldSeatsRequest;
import com.example.tour_service.dto.internal.HoldSeatsResponse;
import com.example.tour_service.dto.internal.InternalDepartureCapacityResponse;
import com.example.tour_service.dto.internal.ReleaseHoldResponse;
import com.example.tour_service.entity.TourDeparture;
import com.example.tour_service.repository.TourDepartureRepository;
import com.example.tour_service.service.DepartureCapacityReader;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/internal/tour/departures")
@RequiredArgsConstructor
public class InternalTourDepartureController {

    private final TourDepartureRepository tourDepartureRepository;
    private final DepartureCapacityReader capacityReader;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    @GetMapping("/{id}/capacity")
    public ResponseEntity<InternalDepartureCapacityResponse> getCapacity(@PathVariable Integer id) {
        Optional<TourDeparture> departureOpt = tourDepartureRepository.findById(id);
        if (departureOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        TourDeparture departure = departureOpt.get();
        DepartureCapacityReader.State state = capacityReader.read(departure, LocalDateTime.now());
        
        InternalDepartureCapacityResponse response = InternalDepartureCapacityResponse.builder()
                .departureId(departure.getId())
                .tourId(departure.getTour().getId())
                .tourCode(departure.getTourCode())
                .departureDate(departure.getDepartureDate())
                .returnDate(departure.getReturnDate())
                .capacity(departure.getCapacity() != null ? departure.getCapacity().shortValue() : null)
                .availableSeats(departure.getAvailableSeats())
                .version((int) departure.getVersion())
                .paidSeats(state.paidSeats())
                .heldSeats(state.heldSeats())
                .overdueHolds(state.overdueHolds())
                .historyCount(state.historyCount())
                .hasHistory(state.relatedHistory())
                .capacityMismatch(state.issues().contains(DepartureCapacityReader.CAPACITY_MISMATCH))
                .issues(state.issues())
                .blockingIssues(state.issues().stream()
                        .filter(issue -> !DepartureCapacityReader.CAPACITY_MISMATCH.equals(issue))
                        .toList())
                .warnings(state.warnings())
                .build();
        
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getDeparture(@PathVariable Integer id) {
        var departure = tourDepartureRepository.findById(id);
        if (departure.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        TourDeparture d = departure.get();
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("id", d.getId());
        payload.put("tour_id", d.getTour().getId());
        payload.put("tour_title", d.getTour().getTourTitle() != null ? d.getTour().getTourTitle() : "");
        payload.put("tour_code", d.getTourCode() != null ? d.getTourCode() : "");
        payload.put("departure_date", d.getDepartureDate());
        payload.put("return_date", d.getReturnDate() != null ? d.getReturnDate() : d.getDepartureDate());
        payload.put("capacity", d.getCapacity() != null ? d.getCapacity() : 0);
        payload.put("available_seats", d.getAvailableSeats() != null ? d.getAvailableSeats() : 0);
        payload.put("operational_status", d.getOperationalStatus() != null ? d.getOperationalStatus() : "OPEN");
        payload.put("version", d.getVersion());
        return ResponseEntity.ok(payload);
    }

    @PostMapping("/{id}/hold")
    @Transactional
    public ResponseEntity<HoldSeatsResponse> holdSeats(
            @PathVariable Integer id,
            @Valid @RequestBody HoldSeatsRequest request) {
        
        Optional<TourDeparture> departureOpt = tourDepartureRepository.lockById(id);
        if (departureOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        
        TourDeparture departure = departureOpt.get();
        DepartureCapacityReader.State state = capacityReader.read(departure, LocalDateTime.now());
        
        if (!"OPEN".equals(departure.getOperationalStatus())) {
            return ResponseEntity.badRequest().body(HoldSeatsResponse.builder()
                    .departureId(id)
                    .success(false)
                    .message("Lịch khởi hành không ở trạng thái OPEN")
                    .build());
        }
        
        if (state.issues().stream().anyMatch(issue -> !DepartureCapacityReader.CAPACITY_MISMATCH.equals(issue))) {
            return ResponseEntity.badRequest().body(HoldSeatsResponse.builder()
                    .departureId(id)
                    .success(false)
                    .message("Có vấn đề chặn việc giữ chỗ: " + String.join("; ", state.issues()))
                    .build());
        }
        
        Integer requestedSeats = request.getSeats();
        if (requestedSeats == null || requestedSeats <= 0) {
            return ResponseEntity.badRequest().body(HoldSeatsResponse.builder()
                    .departureId(id)
                    .success(false)
                    .message("Số chỗ giữ phải lớn hơn 0")
                    .build());
        }
        
        if (departure.getAvailableSeats() < requestedSeats) {
            return ResponseEntity.badRequest().body(HoldSeatsResponse.builder()
                    .departureId(id)
                    .success(false)
                    .message("Không đủ chỗ trống. Còn: " + departure.getAvailableSeats())
                    .build());
        }
        
        departure.setAvailableSeats((short) (departure.getAvailableSeats() - requestedSeats));
        departure.setVersion(departure.getVersion() + 1);
        TourDeparture saved = tourDepartureRepository.saveAndFlush(departure);

        int parsedBookingId = 0;
        if (request.getBookingId() != null) {
            try { parsedBookingId = Integer.parseInt(request.getBookingId().trim()); } catch (NumberFormatException ignored) {}
        }
        LocalDateTime holdNow = LocalDateTime.now();
        LocalDateTime holdExpires = request.getExpiresAt() != null ? request.getExpiresAt() : holdNow.plusMinutes(15);
        jdbc.update("""
                INSERT INTO departure_hold (hold_id, departure_id, booking_id, seats, status, expires_at, version, created_at)
                VALUES (?, ?, ?, ?, 'HELD', ?, 0, ?)
                """, java.util.UUID.randomUUID().toString(), id, parsedBookingId, requestedSeats, holdExpires, holdNow);
        
        return ResponseEntity.ok(HoldSeatsResponse.builder()
                .departureId(id)
                .seatsHeld(requestedSeats)
                .availableSeatsAfterHold(saved.getAvailableSeats())
                .version((int) saved.getVersion())
                .success(true)
                .message("Đã giữ chỗ thành công")
                .build());
    }

    @DeleteMapping("/{id}/hold")
    @Transactional
    public ResponseEntity<ReleaseHoldResponse> releaseHold(
            @PathVariable Integer id,
            @RequestParam Integer seats,
            @RequestParam(required = false) Integer bookingId) {
        
        Optional<TourDeparture> departureOpt = tourDepartureRepository.lockById(id);
        if (departureOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        
        TourDeparture departure = departureOpt.get();
        
        if (seats == null || seats <= 0) {
            return ResponseEntity.badRequest().body(ReleaseHoldResponse.builder()
                    .departureId(id)
                    .success(false)
                    .message("Số chỗ giải phóng phải lớn hơn 0")
                    .build());
        }

        if (bookingId != null && bookingId > 0) {
            int released = jdbc.update(
                    "UPDATE departure_hold SET status = 'RELEASED', version = version + 1 WHERE departure_id = ? AND booking_id = ? AND status IN ('HELD', 'CHECKOUT')",
                    id, bookingId);
            if (released == 0) {
                return ResponseEntity.ok(ReleaseHoldResponse.builder()
                        .departureId(id)
                        .seatsReleased(0)
                        .availableSeatsAfterRelease(departure.getAvailableSeats())
                        .version((int) departure.getVersion())
                        .success(true)
                        .message("Giữ chỗ đã được giải phóng trước đó (idempotent)")
                        .build());
            }
        }
        
        Integer capacity = departure.getCapacity() != null ? departure.getCapacity() : 32767;
        Integer newAvailable = Math.min(capacity, departure.getAvailableSeats() + seats);
        
        departure.setAvailableSeats(newAvailable.shortValue());
        departure.setVersion(departure.getVersion() + 1);
        TourDeparture saved = tourDepartureRepository.saveAndFlush(departure);
        
        return ResponseEntity.ok(ReleaseHoldResponse.builder()
                .departureId(id)
                .seatsReleased(seats)
                .availableSeatsAfterRelease(saved.getAvailableSeats())
                .version((int) saved.getVersion())
                .success(true)
                .message("Đã giải phóng chỗ thành công")
                .build());
    }

    @GetMapping("/users/{userId}/has-history")
    public ResponseEntity<Map<String, Object>> hasUserHistory(@PathVariable Long userId) {
        // Reviews are owned by auth_service (sgu_auth); sgu_tour holds no user-owned history rows.
        return ResponseEntity.ok(Map.of("hasHistory", false));
    }

    @GetMapping("/by-tour/{tourId}")
    public ResponseEntity<List<Map<String, Object>>> getDeparturesByTour(@PathVariable Integer tourId) {
        List<Map<String, Object>> result = tourDepartureRepository.findByTourId(tourId).stream()
                .map(d -> Map.<String, Object>of(
                        "id", d.getId(),
                        "tour_id", d.getTour().getId(),
                        "tour_code", d.getTourCode(),
                        "departure_date", d.getDepartureDate(),
                        "return_date", d.getReturnDate(),
                        "capacity", d.getCapacity() != null ? d.getCapacity() : 0,
                        "available_seats", d.getAvailableSeats(),
                        "operational_status", d.getOperationalStatus(),
                        "version", d.getVersion()
                ))
                .toList();
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{id}/operational-status")
    @Transactional
    public ResponseEntity<Void> updateOperationalStatus(
            @PathVariable Integer id,
            @RequestParam String status) {
        Optional<TourDeparture> departureOpt = tourDepartureRepository.lockById(id);
        if (departureOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        TourDeparture departure = departureOpt.get();
        departure.setOperationalStatus(status);
        departure.setVersion(departure.getVersion() + 1);
        tourDepartureRepository.saveAndFlush(departure);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/replacement")
    @Transactional
    public ResponseEntity<Map<String, Object>> createReplacement(@RequestBody Map<String, Object> req) {
        int tourId = ((Number) req.get("tourId")).intValue();
        String tourCode = String.valueOf(req.get("tourCode"));
        LocalDateTime departureDate = LocalDateTime.parse(String.valueOf(req.get("departureDate")));
        LocalDateTime returnDate = LocalDateTime.parse(String.valueOf(req.get("returnDate")));
        int capacity = ((Number) req.get("capacity")).intValue();
        var tour = tourDepartureRepository.findByTourId(tourId).stream()
                .map(TourDeparture::getTour)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Tour not found: " + tourId));
        var created = tourDepartureRepository.saveAndFlush(TourDeparture.builder()
                .tour(tour)
                .tourCode(tourCode)
                .departureDate(departureDate)
                .returnDate(returnDate)
                .availableSeats((short) capacity)
                .capacity(capacity)
                .operationalStatus("REPLACEMENT_ONLY")
                .build());
        return ResponseEntity.ok(Map.of(
                "id", created.getId(),
                "tour_id", tourId,
                "tour_code", created.getTourCode(),
                "departure_date", created.getDepartureDate(),
                "return_date", created.getReturnDate(),
                "capacity", created.getCapacity(),
                "available_seats", created.getAvailableSeats(),
                "operational_status", created.getOperationalStatus(),
                "version", created.getVersion()
        ));
    }

    @PostMapping("/{id}/increment-version")
    @Transactional
    public ResponseEntity<Void> incrementVersion(@PathVariable Integer id) {
        Optional<TourDeparture> departureOpt = tourDepartureRepository.lockById(id);
        if (departureOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        TourDeparture departure = departureOpt.get();
        departure.setVersion(departure.getVersion() + 1);
        tourDepartureRepository.saveAndFlush(departure);
        return ResponseEntity.ok().build();
    }
}