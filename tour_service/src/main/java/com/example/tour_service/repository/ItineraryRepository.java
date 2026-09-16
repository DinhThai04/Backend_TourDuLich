package com.example.tour_service.repository;

import com.example.tour_service.entity.Itinerary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ItineraryRepository extends JpaRepository<Itinerary, Integer> {
    List<Itinerary> findByTourId(int tourId);

    Itinerary findTopByTourIdOrderByDayNumberDesc(int tourId);
}