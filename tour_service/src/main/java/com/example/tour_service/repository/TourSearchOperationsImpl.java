package com.example.tour_service.repository;

import com.example.tour_service.entity.*;
import jakarta.persistence.*;
import jakarta.persistence.criteria.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Transactional(readOnly = true)
public class TourSearchOperationsImpl implements TourSearchOperations {
    @PersistenceContext private EntityManager entityManager;

    @Override public Page<Tour> findByEarliestDeparture(Specification<Tour> filters, Pageable page, LocalDateTime now, LocalDate day) {
        var cb = entityManager.getCriteriaBuilder();
        var query = cb.createTupleQuery();
        var root = query.from(Tour.class);
        Predicate predicate = filters.toPredicate(root, query, cb);
        Join<Tour, TourDeparture> departure = root.join("tourDepartures", JoinType.LEFT);
        var future = cb.and(cb.greaterThan(departure.<LocalDateTime>get("departureDate"), now),cb.equal(departure.get("operationalStatus"),"OPEN"));
        departure.on(day == null ? future : cb.and(future,
                cb.greaterThanOrEqualTo(departure.get("departureDate"), day.atStartOfDay()),
                cb.lessThan(departure.get("departureDate"), day.plusDays(1).atStartOfDay())));
        Expression<LocalDateTime> first = cb.least(departure.<LocalDateTime>get("departureDate"));
        query.distinct(false); // Group by ID prevents duplicate tours, even when the filters join departures too.
        query.multiselect(root.get("id"), first).where(predicate).groupBy(root.get("id"))
                .orderBy(cb.asc(cb.<Integer>selectCase().when(cb.isNull(first), 1).otherwise(0)), cb.asc(first), cb.asc(root.get("id")));
        var ids = entityManager.createQuery(query).setFirstResult(Math.toIntExact(page.getOffset())).setMaxResults(page.getPageSize())
                .getResultList().stream().map(row -> row.get(0, Integer.class)).toList();
        var count = cb.createQuery(Long.class);
        var counted = count.from(Tour.class);
        count.select(cb.countDistinct(counted.get("id"))).where(filters.toPredicate(counted, count, cb));
        count.distinct(false);
        long total = entityManager.createQuery(count).getSingleResult();
        if (ids.isEmpty()) return new PageImpl<>(List.of(), page, total);
        var fetched = entityManager.createQuery("""
                        select t from Tour t
                        left join fetch t.departureLocation
                        left join fetch t.destinationLocation
                        left join fetch t.vehicle
                        where t.id in :ids
                        """, Tour.class).setParameter("ids", ids)
                .getResultList().stream().collect(Collectors.toMap(Tour::getId, Function.identity()));
        // A tour deleted between the id query and the fetch must not surface as a null row.
        return new PageImpl<>(ids.stream().map(fetched::get).filter(java.util.Objects::nonNull).toList(), page, total);
    }
}
