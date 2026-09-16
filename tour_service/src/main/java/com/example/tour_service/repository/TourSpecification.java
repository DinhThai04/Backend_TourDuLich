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
import java.util.stream.Collectors;

@Component
public class TourSpecification {
    public static Specification<Tour> build(TourSearchRequest request) {
        return (root, query, criteriaBuilder) -> {

            List<Predicate> predicates = new ArrayList<>();

            // 1. Lọc theo Điểm đến
            if (request.getDest() != null && !request.getDest().isEmpty()) {
                Join<Tour, Location> destinationLocation = root.join("destinationLocation");

                predicates.add(criteriaBuilder.like(
                        criteriaBuilder.lower(destinationLocation.get("city")),
                        "%" + request.getDest().toLowerCase() + "%"
                ));
            }

            // 2. Lọc theo Điểm khởi hành
            if (request.getDep() != null && !request.getDep().isEmpty()) {

                Join<Tour, Location> departureLocation = root.join("departureLocation");

                predicates.add(criteriaBuilder.like(
                        criteriaBuilder.lower(departureLocation.get("city")),
                        "%" + request.getDep().toLowerCase() + "%"
                ));
            }

            // 3. Lọc theo Ngày khởi hành (chính xác)
            if (request.getDepD() != null) {

                // 3a. Join với bảng TourDeparture
                Join<Tour, TourDeparture> tourDepartureJoin = root.join("tourDepartures");

                // 3b. Tránh 1 tour lặp lại nhiều lần nếu nó có 2 chuyến trong ngày
                query.distinct(true);

                // 3c. Lấy thời điểm 00:00:00 của ngày được chọn
                // (ví dụ: 2025-11-06T00:00:00)
                LocalDateTime startOfDay = request.getDepD().atStartOfDay();

                // 3d. Lấy thời điểm 00:00:00 của ngày TIẾP THEO
                // (ví dụ: 2025-11-07T00:00:00)
                LocalDateTime endOfDay = request.getDepD().plusDays(1).atStartOfDay();

                // 3e. Tìm tất cả các chuyến có 'departureDate' >= startOfDay VÀ < endOfDay
                Predicate datePredicate = criteriaBuilder.and(
                        criteriaBuilder.greaterThanOrEqualTo(tourDepartureJoin.get("departureDate"), startOfDay),
                        criteriaBuilder.lessThan(tourDepartureJoin.get("departureDate"), endOfDay)
                );

                predicates.add(datePredicate);
            }

            // 4. Lọc theo Phương tiện
            if (request.getVeh() != null && !request.getVeh().isEmpty()) {


                // 1. Tách chuỗi thành List<String>
                List<String> vehicleIdStrings = Arrays.asList(request.getVeh().split(","));

                // 2. Chuyển đổi List<String> thành List<Integer> (hoặc Long)
                // (Giả sử ID của Vehicle là Integer, nếu là Long thì dùng Long::parseLong)
                List<Integer> vehicleIds = vehicleIdStrings.stream()
                        .map(Integer::parseInt)
                        .collect(Collectors.toList());

                // 3. Tạo một Join từ Tour (root) đến Vehicle
                // "vehicle" là tên trường trong Entity Tour
                Join<Tour, Vehicle> vehicleJoin = root.join("vehicle");

                // 4. Lọc trên trường "id" của Vehicle
                // "id" là tên trường trong Entity Vehicle
                predicates.add(vehicleJoin.get("id").in(vehicleIds));
            }

            // 7. Lọc theo Tên tour
            if (request.getTitle() != null  && !request.getTitle().isEmpty()) {
                predicates.add(criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("tourTitle")),
                        "%" + request.getTitle().toLowerCase() + "%"
                ));
            }

            // 5. Lọc theo Giá thấp nhất (basePrice >= minPrice)
            if (request.getMinP() != null) {
                predicates.add(criteriaBuilder.greaterThanOrEqualTo(
                        root.get("basePrice"),
                        request.getMinP()
                ));
            }

            // 6. Lọc theo Giá cao nhất (basePrice <= maxPrice)
            if (request.getMaxP() != null) {
                predicates.add(criteriaBuilder.lessThanOrEqualTo(
                        root.get("basePrice"),
                        request.getMaxP()
                ));
            }

            // Kết hợp tất cả điều kiện bằng AND
            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };
    }
}
