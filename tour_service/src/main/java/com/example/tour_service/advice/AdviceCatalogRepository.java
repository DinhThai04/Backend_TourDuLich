package com.example.tour_service.advice;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Reads only tables owned by the tour service; prices and media come from their APIs. */
@Repository
@Transactional(readOnly=true)
public class AdviceCatalogRepository {
    private static final int MAX_PLACES = 2000;
    public record Place(int id, String name, String type) {}
    public record Candidate(int id, String program, String title, int duration, Long priceId,
                            Integer originId, Integer destinationId) {}
    public record Departure(int id, int tourId, LocalDateTime at, LocalDateTime returnsAt, Integer seats) {}
    private final NamedParameterJdbcTemplate jdbc;
    private final AdviceConfiguration.Limits limits;
    public AdviceCatalogRepository(NamedParameterJdbcTemplate jdbc, AdviceConfiguration.Limits limits) {
        this.jdbc = jdbc; this.limits = limits;
    }
    public List<Place> places() {
        var rows = jdbc.query("SELECT id,city,type FROM location ORDER BY id LIMIT :limit",
                Map.of("limit", MAX_PLACES + 1),
                (rs,n) -> new Place(rs.getInt("id"), rs.getString("city"), rs.getString("type")));
        if (rows.size() > MAX_PLACES) throw AdviceException.unavailable();
        return rows;
    }
    public List<Candidate> candidates() {
        var rows = jdbc.query("SELECT id,tour_program,tour_title,duration,price_id,departure_location_id,destination_id FROM tour ORDER BY id LIMIT :limit",
                Map.of("limit", limits.maxCandidateTours()+1), (rs,n) -> new Candidate(rs.getInt("id"),
                        rs.getString("tour_program"),rs.getString("tour_title"),rs.getInt("duration"),
                        rs.getObject("price_id",Long.class),rs.getObject("departure_location_id",Integer.class),
                        rs.getObject("destination_id",Integer.class)));
        if (rows.size() > limits.maxCandidateTours()) throw AdviceException.unavailable();
        return rows;
    }
    public List<Departure> futureDepartures(List<Integer> tourIds, LocalDateTime now) {
        if (tourIds.isEmpty()) return List.of();
        var rows = jdbc.query("""
            SELECT id,tour_id,departure_date,return_date,available_seats FROM tour_departure
            WHERE tour_id IN (:ids) AND operational_status='OPEN' AND (departure_date>:now OR departure_date IS NULL OR return_date IS NULL)
            ORDER BY departure_date,id LIMIT :limit
            """, Map.of("ids", tourIds, "now", now, "limit", limits.maxFutureDepartures()+1),
                (rs,n) -> new Departure(rs.getInt("id"),rs.getInt("tour_id"),
                        rs.getObject("departure_date",LocalDateTime.class),rs.getObject("return_date",LocalDateTime.class),
                        rs.getObject("available_seats",Integer.class)));
        if (rows.size() > limits.maxFutureDepartures()) throw AdviceException.unavailable();
        return rows;
    }
}
