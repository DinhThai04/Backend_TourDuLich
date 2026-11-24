package com.example.tour_service.service;

import com.example.tour_service.dto.internal.InternalPriceResponse;
import com.example.tour_service.repository.httpclient.PricingInternalClient;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

@Service
public class SearchCatalogSnapshot {
    private static final int PRICING_BATCH_SIZE = 100;
    private static final int MAX_TOURS = 10_000;
    private static final int MAX_DEPARTURES = 100_000;

    public record Entry(int id, String tourProgram, String tourTitle, BigDecimal basePrice,
                        String departureLocation, String destinationLocation, String vehicle,
                        List<String> departureDates) {}

    public record Snapshot(String source, boolean complete, int total, List<Entry> tours) {}

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final PricingInternalClient pricingClient;
    private final TransactionTemplate localSnapshotTransaction;

    public SearchCatalogSnapshot(JdbcTemplate jdbc, Clock clock, PricingInternalClient pricingClient,
                                 PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.pricingClient = pricingClient;
        this.localSnapshotTransaction = new TransactionTemplate(transactionManager);
        this.localSnapshotTransaction.setReadOnly(true);
        this.localSnapshotTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public Snapshot read() {
        LocalSnapshot local = localSnapshotTransaction.execute(status -> readLocalSnapshot());
        if (local == null) throw unavailable();

        Map<Long, InternalPriceResponse> prices = loadPrices(local.priceIds());
        List<Entry> entries = local.tours().stream()
                .map(row -> toEntry(row, prices, local.departureDates()))
                .toList();
        return new Snapshot("SGU_TOUR_CATALOG_V1", true, entries.size(), entries);
    }

    private LocalSnapshot readLocalSnapshot() {
        List<Map<String, Object>> tours = jdbc.queryForList("""
                SELECT t.id, t.tour_program, t.tour_title, t.price_id,
                       departure.city AS departure_city,
                       destination.city AS destination_city,
                       vehicle.vehicle_type AS vehicle
                FROM tour t
                LEFT JOIN location departure ON departure.id = t.departure_location_id
                LEFT JOIN location destination ON destination.id = t.destination_id
                LEFT JOIN vehicles vehicle ON vehicle.id = t.vehicle_id
                ORDER BY t.id
                LIMIT ?
                """, MAX_TOURS + 1);
        if (tours.size() > MAX_TOURS) throw unavailable();

        List<Map<String, Object>> departures = jdbc.queryForList("""
                SELECT tour_id, departure_date
                FROM tour_departure
                WHERE operational_status = 'OPEN' AND available_seats > 0 AND departure_date > ?
                ORDER BY tour_id, departure_date
                LIMIT ?
                """, LocalDateTime.now(clock), MAX_DEPARTURES + 1);
        if (departures.size() > MAX_DEPARTURES) throw unavailable();

        Map<Integer, SortedSet<String>> datesByTour = new HashMap<>();
        for (Map<String, Object> departure : departures) {
            if (!(departure.get("tour_id") instanceof Number tourId)) throw unavailable();
            LocalDateTime departureAt = toLocalDateTime(departure.get("departure_date"));
            datesByTour.computeIfAbsent(tourId.intValue(), ignored -> new TreeSet<>())
                    .add(departureAt.toLocalDate().toString());
        }

        LinkedHashSet<Long> priceIds = new LinkedHashSet<>();
        for (Map<String, Object> tour : tours) {
            if (!(tour.get("price_id") instanceof Number priceId) || priceId.longValue() < 1)
                throw unavailable();
            priceIds.add(priceId.longValue());
        }
        return new LocalSnapshot(tours, datesByTour, List.copyOf(priceIds));
    }

    private Map<Long, InternalPriceResponse> loadPrices(List<Long> priceIds) {
        Map<Long, InternalPriceResponse> prices = new HashMap<>();
        for (int start = 0; start < priceIds.size(); start += PRICING_BATCH_SIZE) {
            List<Long> batch = priceIds.subList(start, Math.min(start + PRICING_BATCH_SIZE, priceIds.size()));
            List<InternalPriceResponse> response;
            try {
                response = pricingClient.getPricesBatch(batch);
            } catch (RuntimeException upstreamFailure) {
                throw unavailable();
            }
            if (response == null) throw unavailable();

            for (InternalPriceResponse price : response) {
                if (price == null || price.id() == null || !batch.contains(price.id())
                        || prices.putIfAbsent(price.id(), price) != null) throw unavailable();
            }
        }
        if (!prices.keySet().containsAll(priceIds)) throw unavailable();
        return prices;
    }

    private Entry toEntry(Map<String, Object> tour, Map<Long, InternalPriceResponse> prices,
                          Map<Integer, SortedSet<String>> departureDates) {
        if (!(tour.get("id") instanceof Number rawTourId)
                || !(tour.get("price_id") instanceof Number rawPriceId)) throw unavailable();
        int tourId = rawTourId.intValue();
        InternalPriceResponse price = prices.get(rawPriceId.longValue());
        Object title = tour.get("tour_title");
        Object departure = tour.get("departure_city");
        Object destination = tour.get("destination_city");
        Object vehicle = tour.get("vehicle");
        if (price == null || price.adultPrice() == null || price.adultPrice().signum() <= 0
                || title == null || departure == null || destination == null || vehicle == null)
            throw unavailable();

        return new Entry(tourId, (String) tour.get("tour_program"), (String) title, price.adultPrice(),
                (String) departure, (String) destination, (String) vehicle,
                List.copyOf(departureDates.getOrDefault(tourId, Collections.emptySortedSet())));
    }

    private static LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof LocalDateTime localDateTime) return localDateTime;
        if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime();
        throw unavailable();
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Chưa đọc được snapshot danh mục đầy đủ và hợp lệ");
    }

    private record LocalSnapshot(List<Map<String, Object>> tours,
                                 Map<Integer, SortedSet<String>> departureDates,
                                 List<Long> priceIds) {}
}
