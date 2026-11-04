package com.example.tour_service.repository;
import com.example.tour_service.dto.request.TourSearchRequest;
import com.example.tour_service.entity.Location;
import com.example.tour_service.entity.Tour;
import com.example.tour_service.entity.TourDeparture;
import com.example.tour_service.entity.Vehicle;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Component
public class TourSpecification {

    /**
     * Escape character for LIKE, so a user typing "%" or "_" searches for the literal
     * character instead of matching every tour.
     */
    private static final char LIKE_ESCAPE = '\\';

    static String likePattern(String value) {
        String escaped = value.toLowerCase(java.util.Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }

    public static Specification<Tour> build(TourSearchRequest request, LocalDateTime now) {
        return (root, query, criteriaBuilder) -> {

            List<Predicate> predicates = new ArrayList<>();

            // 1. Lọc theo Điểm đến
            if (request.getDest() != null && !request.getDest().isEmpty()) {
                Join<Tour, Location> destinationLocation = root.join("destinationLocation");

                predicates.add(criteriaBuilder.like(
                        criteriaBuilder.lower(destinationLocation.get("city")),
                        likePattern(request.getDest()),
                        LIKE_ESCAPE
                ));
            }

            // 2. Lọc theo Điểm khởi hành
            if (request.getDep() != null && !request.getDep().isEmpty()) {

                Join<Tour, Location> departureLocation = root.join("departureLocation");

                predicates.add(criteriaBuilder.like(
                        criteriaBuilder.lower(departureLocation.get("city")),
                        likePattern(request.getDep()),
                        LIKE_ESCAPE
                ));
            }

            // 3. Lọc theo Ngày khởi hành (chính xác)
            if (request.getDepD() != null) {

                // EXISTS avoids duplicate tours and DISTINCT/ORDER BY conflicts when sorting by the joined adult price.
                var dates = query.subquery(Integer.class);
                var departure = dates.from(TourDeparture.class);
                LocalDateTime startOfDay = request.getDepD().atStartOfDay();
                LocalDateTime endOfDay = request.getDepD().plusDays(1).atStartOfDay();
                dates.select(criteriaBuilder.literal(1)).where(
                        criteriaBuilder.equal(departure.get("tour"), root),
                        criteriaBuilder.equal(departure.get("operationalStatus"), "OPEN"),
                        // A past date must not match: the catalog sorts and labels by the next bookable
                        // departure, so including departed schedules would list tours with no date.
                        criteriaBuilder.greaterThan(departure.get("departureDate"), now),
                        criteriaBuilder.greaterThanOrEqualTo(departure.get("departureDate"), startOfDay),
                        criteriaBuilder.lessThan(departure.get("departureDate"), endOfDay));
                predicates.add(criteriaBuilder.exists(dates));
            }

            // 4. Lọc theo Phương tiện
            if (request.getVeh() != null && !request.getVeh().isEmpty()) {
                List<String> vehicleIds = Arrays.stream(request.getVeh().split(","))
                        .map(String::trim)
                        .filter(id -> !id.isEmpty())
                        .toList();
                if (!vehicleIds.isEmpty()) {
                    Join<Tour, Vehicle> vehicleJoin = root.join("vehicle");
                    predicates.add(vehicleJoin.get("id").in(vehicleIds));
                }
            }

            // 7. Lọc theo Tên tour
            if (request.getTitle() != null  && !request.getTitle().isEmpty()) {
                predicates.add(criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("tourTitle")),
                        likePattern(request.getTitle()),
                        LIKE_ESCAPE
                ));
            }

            // 5. Lọc theo Giá thấp nhất (basePrice >= minPrice)
            if (request.getMinP() != null) {
                predicates.add(criteriaBuilder.greaterThanOrEqualTo(
                        root.join("priceView").get("adultPrice"),
                        request.getMinP()
                ));
            }

            // 6. Lọc theo Giá cao nhất (basePrice <= maxPrice)
            if (request.getMaxP() != null) {
                predicates.add(criteriaBuilder.lessThanOrEqualTo(
                        root.join("priceView").get("adultPrice"),
                        request.getMaxP()
                ));
            }

            // Kết hợp tất cả điều kiện bằng AND
            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };
    }
}
