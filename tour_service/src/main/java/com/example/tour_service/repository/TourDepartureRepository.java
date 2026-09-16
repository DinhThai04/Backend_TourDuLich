package com.example.tour_service.repository;

import com.example.tour_service.entity.Itinerary;
import com.example.tour_service.entity.Tour;
import com.example.tour_service.entity.TourDeparture;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TourDepartureRepository extends JpaRepository<TourDeparture, Integer> {
    int countByTourId(int tourId);
    List<TourDeparture> findByTourId(int tourId);
    List<TourDeparture> findByTourIdIn(List<Integer> tourIds);

}
