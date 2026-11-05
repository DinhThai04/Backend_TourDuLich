package com.example.tour_service.repository;

import com.example.tour_service.entity.Vehicle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VehicleRepository extends JpaRepository<Vehicle, String> {
    /**
     * Insert only: saving an entity with an assigned id merges, so a row created a moment earlier
     * would be quietly overwritten instead of reported as the duplicate it is.
     */
    @Modifying
    @Query("insert into Vehicle (id, vehicleType) values (:id, :vehicleType)")
    int insertNew(@Param("id") String id, @Param("vehicleType") String vehicleType);
}
