package com.example.tour_service.repository;

import com.example.tour_service.entity.Location;
import com.example.tour_service.enums.LocationType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface LocationRepository extends JpaRepository<Location, Integer> {
    List<Location> findByType(LocationType type);
    List<Location> findByTypeAndCityContainsIgnoreCase(LocationType type, String cityKeyword);

    @Query(value = "SELECT * FROM location WHERE location.type = 'DESTINATION' AND img IS NOT NULL AND img != '' ORDER BY RAND() LIMIT 7", nativeQuery = true)
    List<Location> findRandom7Locations();
}
