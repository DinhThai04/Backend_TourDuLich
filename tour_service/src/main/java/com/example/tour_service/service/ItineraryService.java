package com.example.tour_service.service;

import com.example.tour_service.dto.request.ItineraryRequest;
import com.example.tour_service.dto.response.ItineraryResponse;
import com.example.tour_service.entity.Itinerary;
import com.example.tour_service.entity.Tour;
import com.example.tour_service.exception.AppException;
import com.example.tour_service.exception.ErrorCode;
import com.example.tour_service.repository.ItineraryRepository;
import com.example.tour_service.repository.TourRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
@RequiredArgsConstructor
public class ItineraryService {
    private static final int MAX_LIST_SIZE = 2000;

    private final ItineraryRepository itineraryRepository;
    private final TourRepository tourRepository;

    public ItineraryResponse getById(int id) {
        Itinerary itinerary = itineraryRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.ITINERARY_NOT_FOUND));
        return toResponse(itinerary);
    }

    public List<ItineraryResponse> getByTourId(int tourId) {
        return itineraryRepository.findByTourIdOrderByDayNumberAscIdAsc(tourId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

     public List<ItineraryResponse> getAll() {
         // Bounded read: the admin list must not degrade as the number of tours grows.
         // Newest tours first, so a capped page still shows the rows operators just created.
         return itineraryRepository.findAll(PageRequest.of(0, MAX_LIST_SIZE,
                         Sort.by(Sort.Direction.DESC, "tour.id").and(Sort.by("dayNumber", "id")))).stream()
         .map(this::toResponse)
         .collect(Collectors.toList());
     }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ItineraryResponse create(ItineraryRequest request) {
        // A stable parent row serializes day allocation even when this tour has no itinerary yet.
        Tour tour = tourRepository.lockForItinerary(request.getTourId())
                .orElseThrow(() -> new AppException(ErrorCode.TOUR_NOT_FOUND));

        // Fill the first free day so deleting a middle day does not burn the last slot of a tour.
        Set<Integer> usedDays = itineraryRepository.findByTourIdOrderByDayNumberAscIdAsc(tour.getId()).stream()
                .map(Itinerary::getDayNumber).collect(Collectors.toSet());
        int nextDayNumber = IntStream.rangeClosed(1, tour.getDuration())
                .filter(day -> !usedDays.contains(day))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Lịch trình đã đủ thời lượng " + tour.getDuration() + " ngày của tour"));

        Itinerary itinerary = Itinerary.builder()
                .dayNumber(nextDayNumber)
                .title(request.getTitle())
                .description(request.getDescription())
                .meal(request.getMeal())
                .tour(tour)
                .build();

        return toResponse(itineraryRepository.save(itinerary));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ItineraryResponse update(int id, ItineraryRequest request) {
        int actualTourId = lockItineraryTour(id);
        // The request carries its parent tour; a mismatch means a stale editor, not a tour move.
        if (request.getTourId() != actualTourId) {
            throw new IllegalArgumentException("Tour của lịch trình không khớp; vui lòng tải lại");
        }
        Itinerary itinerary = itineraryRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.ITINERARY_NOT_FOUND));

        itinerary.setTitle(request.getTitle());
        itinerary.setDescription(request.getDescription());
        itinerary.setMeal(request.getMeal());

        return toResponse(itineraryRepository.save(itinerary));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void delete(int id) {
        lockItineraryTour(id);
        if (!itineraryRepository.existsById(id)) {
            throw new AppException(ErrorCode.ITINERARY_NOT_FOUND);
        }
        itineraryRepository.deleteById(id);
    }

    private int lockItineraryTour(int id) {
        int tourId = itineraryRepository.findTourIdByItineraryId(id)
                .orElseThrow(() -> new AppException(ErrorCode.ITINERARY_NOT_FOUND));
        tourRepository.lockForItinerary(tourId)
                .orElseThrow(() -> new AppException(ErrorCode.TOUR_NOT_FOUND));
        return tourId;
    }

    public ItineraryResponse toResponse(Itinerary itinerary) {
        return ItineraryResponse.builder()
                .id(itinerary.getId())
                .dayNumber(itinerary.getDayNumber())
                .title(itinerary.getTitle())
                .description(itinerary.getDescription())
                .meal(itinerary.getMeal())
                .tourId(itinerary.getTour().getId())
                .build();
    }
}
