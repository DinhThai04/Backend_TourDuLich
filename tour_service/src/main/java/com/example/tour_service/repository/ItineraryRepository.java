package com.example.tour_service.repository;

import com.example.tour_service.entity.Itinerary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ItineraryRepository extends JpaRepository<Itinerary, Integer> {
    List<Itinerary> findByTourId(int tourId);
    List<Itinerary> findByTourIdOrderByDayNumberAscIdAsc(int tourId);

    @Query("select i.tour.id from Itinerary i where i.id=:id")
    Optional<Integer> findTourIdByItineraryId(@Param("id") int id);

    Itinerary findTopByTourIdOrderByDayNumberDesc(int tourId);

    @Modifying
    @Query("delete from Itinerary i where i.tour.id = :tourId")
    int deleteByTourId(@Param("tourId") int tourId);
}
