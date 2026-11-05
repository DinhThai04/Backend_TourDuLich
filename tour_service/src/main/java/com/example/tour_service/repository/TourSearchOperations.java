package com.example.tour_service.repository;

import com.example.tour_service.entity.Tour;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import java.time.*;

public interface TourSearchOperations {
    Page<Tour> findByEarliestDeparture(Specification<Tour> filters, Pageable page, LocalDateTime now, LocalDate day);
}
