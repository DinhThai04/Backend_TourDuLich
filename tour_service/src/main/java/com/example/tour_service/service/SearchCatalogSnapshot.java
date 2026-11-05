package com.example.tour_service.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Public catalog facts only. One consistent DB snapshot, no dependency on media/model/search services. */
@Service
public class SearchCatalogSnapshot {
    public record Entry(int id,String tourProgram,String tourTitle,BigDecimal basePrice,String departureLocation,
                        String destinationLocation,String vehicle,List<String> departureDates) {}
    public record Snapshot(String source,boolean complete,int total,List<Entry> tours) {}
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public SearchCatalogSnapshot(JdbcTemplate jdbc, Clock clock) { this.jdbc=jdbc; this.clock=clock; }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Snapshot read() {
        var rows=jdbc.queryForList("""
            SELECT t.id,t.tour_program,t.tour_title,p.adult_price,l.city AS departure_city,
                   d.city AS destination_city,v.vehicle_type AS vehicle
            FROM tour t LEFT JOIN prices p ON p.id=t.price_id
            LEFT JOIN location l ON l.id=t.departure_location_id LEFT JOIN location d ON d.id=t.destination_id
            LEFT JOIN vehicles v ON v.id=t.vehicle_id ORDER BY t.id LIMIT 10001
            """);
        if(rows.size()>10000) throw unavailable();
        Map<Integer,SortedSet<String>> dates=new HashMap<>();
        var departures=jdbc.queryForList("""
            SELECT tour_id,departure_date FROM tour_departure
            WHERE operational_status='OPEN' AND available_seats>0 AND departure_date>?
            ORDER BY tour_id,departure_date LIMIT 100001
            """,LocalDateTime.now(clock));
        if(departures.size()>100000) throw unavailable();
        for(var departure:departures) {
            var raw=departure.get("departure_date");
            var time=raw instanceof LocalDateTime t?t:((java.sql.Timestamp)raw).toLocalDateTime();
            dates.computeIfAbsent(((Number)departure.get("tour_id")).intValue(),id->new TreeSet<>()).add(time.toLocalDate().toString());
        }
        var result=new ArrayList<Entry>();
        for(var r:rows) {
            var price=r.get("adult_price");
            if(price==null || new BigDecimal(price.toString()).signum()<=0 || r.get("tour_title")==null
                    || r.get("departure_city")==null || r.get("destination_city")==null || r.get("vehicle")==null) throw unavailable();
            int id=((Number)r.get("id")).intValue();
            result.add(new Entry(id,(String)r.get("tour_program"),(String)r.get("tour_title"),new BigDecimal(price.toString()),
                    (String)r.get("departure_city"),(String)r.get("destination_city"),(String)r.get("vehicle"),
                    List.copyOf(dates.getOrDefault(id,Collections.emptySortedSet()))));
        }
        return new Snapshot("SGU_TOUR_CATALOG_V1",true,result.size(),List.copyOf(result));
    }
    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Chưa đọc được snapshot danh mục đầy đủ và hợp lệ");
    }
}
