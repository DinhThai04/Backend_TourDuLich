package com.example.tour_service.repository;

import com.example.tour_service.entity.Tour;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

public interface TourRepository extends JpaRepository<Tour, Integer>, JpaSpecificationExecutor<Tour>, TourSearchOperations {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Tour t where t.id=:id")
    Optional<Tour> lockForItinerary(@Param("id") int id);

    // Let the database pick three rows instead of loading the whole catalogue into memory.
    // The fetch joins keep the DTO mapping valid without an open-in-view session.
    @Query("select t from Tour t left join fetch t.departureLocation left join fetch t.destinationLocation "
            + "left join fetch t.vehicle order by function('RAND')")
    List<Tour> findRandom3(Pageable limit);

    @Query("select t.id from Tour t")
    List<Integer> findAllIds();

    // The catalogue DTO reads these associations, so load them with the tour instead of one query per row.
    @Override
    @EntityGraph(attributePaths = {"departureLocation", "destinationLocation", "vehicle"})
    Optional<Tour> findById(Integer id);

    @Override
    @EntityGraph(attributePaths = {"departureLocation", "destinationLocation", "vehicle"})
    Page<Tour> findAll(Pageable pageable);

    @Override
    @EntityGraph(attributePaths = {"departureLocation", "destinationLocation", "vehicle"})
    Page<Tour> findAll(Specification<Tour> spec, Pageable pageable);

    /** Tours list pickable places by type, so a referenced location must not silently change kind. */
    @Query("select count(t) from Tour t where t.departureLocation.id = :locationId or t.destinationLocation.id = :locationId")
    long countReferencingLocation(@Param("locationId") int locationId);
}
