package com.example.tour_service.repository;

import com.example.tour_service.dto.response.UpcomingDepartureResponse;
import com.example.tour_service.entity.TourDeparture;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface TourDepartureRepository extends JpaRepository<TourDeparture, Integer> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select d from TourDeparture d where d.id=:id")
    java.util.Optional<TourDeparture> lockById(@org.springframework.data.repository.query.Param("id") int id);
    int countByTourId(int tourId);
    List<TourDeparture> findByTourId(int tourId);
    /** Admin view: every departure of a tour, including past/suspended/cancelled ones. */
    List<TourDeparture> findByTourId(int tourId, org.springframework.data.domain.Pageable pageable);
    boolean existsByTourIdAndDepartureDate(int tourId, LocalDateTime departureDate);

    @Query("""
            select new com.example.tour_service.dto.response.UpcomingDepartureResponse(
                d.id, t.id, t.tourTitle, d.tourCode, d.departureDate, d.availableSeats)
            from TourDeparture d join d.tour t
            where d.departureDate >= :from and d.departureDate < :until
              and d.operationalStatus = 'OPEN'
            order by d.departureDate, d.id
            """)
    Slice<UpcomingDepartureResponse> findUpcoming(@Param("from") LocalDateTime from,
                                                   @Param("until") LocalDateTime until, Pageable page);

    /** Storefront and catalogue only show future, open departures; let the database filter them. */
    @Query("""
            select d from TourDeparture d
            join fetch d.tour
            where d.tour.id in :tourIds
              and d.departureDate > :now
              and d.operationalStatus = 'OPEN'
            order by d.departureDate, d.id
            """)
    List<TourDeparture> findOpenFutureByTourIds(@Param("tourIds") List<Integer> tourIds,
                                                @Param("now") LocalDateTime now);

    @Query(value = "SELECT COUNT(*) FROM reviews WHERE user_id = :userId", nativeQuery = true)
    long countReviewsByUserId(@Param("userId") Long userId);
}
