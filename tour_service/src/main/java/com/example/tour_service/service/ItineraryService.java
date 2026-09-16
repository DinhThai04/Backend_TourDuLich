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
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ItineraryService {
    private final ItineraryRepository itineraryRepository;
    private final TourRepository tourRepository;

    public ItineraryResponse getById(int id) {
        Itinerary itinerary = itineraryRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.INVALID_KEY));
        return toResponse(itinerary);
    }

    public List<ItineraryResponse> getByTourId(int tourId) {
        return itineraryRepository.findByTourId(tourId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

     public List<ItineraryResponse> getAll() {
         return itineraryRepository.findAll().stream()
         .map(this::toResponse)
         .collect(Collectors.toList());
     }

    public ItineraryResponse create(ItineraryRequest request) {
        Tour tour = tourRepository.findById(request.getTourId())
                .orElseThrow(() -> new AppException(ErrorCode.INVALID_KEY));

        Itinerary lastItinerary = itineraryRepository.findTopByTourIdOrderByDayNumberDesc(tour.getId());

        int nextDayNumber = (lastItinerary == null) ? 1 : lastItinerary.getDayNumber() + 1;

        Itinerary itinerary = Itinerary.builder()
                .dayNumber(nextDayNumber)
                .title(request.getTitle())
                .description(request.getDescription())
                .meal(request.getMeal())
                .tour(tour)
                .build();

        return toResponse(itineraryRepository.save(itinerary));
    }

    public ItineraryResponse update(int id, ItineraryRequest request) {
        Itinerary itinerary = itineraryRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.INVALID_KEY));

        itinerary.setTitle(request.getTitle());
        itinerary.setDescription(request.getDescription());
        itinerary.setMeal(request.getMeal());

        return toResponse(itineraryRepository.save(itinerary));
    }

    public void delete(int id) {
        if (!itineraryRepository.existsById(id)) {
            throw new AppException(ErrorCode.INVALID_KEY);
        }
        itineraryRepository.deleteById(id);
    }

    public ItineraryResponse toResponse(Itinerary itinerary) {
        return ItineraryResponse.builder()
                .id(itinerary.getId())
                .dayNumber(itinerary.getDayNumber())
                .title(itinerary.getTitle())
                .description(itinerary.getDescription())
                .meal(itinerary.getMeal())
                .build();
    }
}