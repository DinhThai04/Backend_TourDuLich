package com.example.pricing_service.repository;

import com.example.pricing_service.entity.TourPrice;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TourPriceRepository extends JpaRepository<TourPrice, Long> {

    /**
     * Reads the price row with a write lock, so two admins editing the same price
     * cannot overwrite each other's changes.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from TourPrice p where p.id = :id")
    Optional<TourPrice> lockById(@Param("id") Long id);
}
