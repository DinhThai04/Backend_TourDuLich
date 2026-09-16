package com.example.pricing_service.repository;

import com.example.pricing_service.entity.TourPrice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TourPriceRepository extends JpaRepository<TourPrice, Long> {
}
