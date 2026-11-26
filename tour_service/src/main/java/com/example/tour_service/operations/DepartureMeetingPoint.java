package com.example.tour_service.operations;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public record DepartureMeetingPoint(String address, String landmark,
        LocalTime arrivalTime, String directionsUrl) {
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @JsonProperty(value = "confirmationKey", access = JsonProperty.Access.READ_ONLY)
    public String confirmationKey() {
        String value = Stream.of(address, landmark, arrivalTime == null ? null : CLOCK.format(arrivalTime), directionsUrl)
                .map(field -> field == null ? "-1:" : field.length() + ":" + field).collect(Collectors.joining());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static DepartureMeetingPoint read(JdbcTemplate jdbc, int departureId) {
        return query(jdbc, """
                SELECT address,landmark,arrival_time,directions_url FROM departure_meeting_point WHERE departure_id=?
                """, departureId).stream().findFirst().orElse(null);
    }

    public static List<DepartureMeetingPoint> options(JdbcTemplate jdbc, int departureId) {
        return query(jdbc, """
                SELECT address,landmark,arrival_time,directions_url FROM (
                  SELECT address,landmark,arrival_time,directions_url,1 AS stop_order
                    FROM departure_meeting_point WHERE departure_id=?
                  UNION ALL
                  SELECT address,landmark,CAST(arrival_time AS CHAR CHARACTER SET utf8mb4) COLLATE utf8mb4_unicode_ci,directions_url,stop_order
                    FROM departure_extra_meeting_point WHERE departure_id=?
                ) stops ORDER BY stop_order
                """, departureId, departureId);
    }

    static List<DepartureMeetingPoint> publicOptions(JdbcTemplate jdbc, int departureId, Integer tourId) {
        return query(jdbc, """
                SELECT p.address,p.landmark,p.arrival_time,p.directions_url
                FROM (
                  SELECT departure_id,address,landmark,arrival_time,directions_url,1 AS stop_order
                    FROM departure_meeting_point WHERE departure_id=?
                  UNION ALL
                  SELECT departure_id,address,landmark,CAST(arrival_time AS CHAR CHARACTER SET utf8mb4) COLLATE utf8mb4_unicode_ci,directions_url,stop_order
                    FROM departure_extra_meeting_point WHERE departure_id=?
                ) p JOIN tour_departure d ON d.id=p.departure_id
                WHERE d.operational_status='OPEN' AND d.departure_date>? AND (? IS NULL OR d.tour_id=?)
                ORDER BY p.stop_order
                """, departureId, departureId, LocalDateTime.now(BUSINESS_ZONE), tourId, tourId);
    }

    static DepartureMeetingPoint readPublic(JdbcTemplate jdbc, int departureId, Integer tourId) {
        return query(jdbc, """
                SELECT p.address,p.landmark,p.arrival_time,p.directions_url
                FROM departure_meeting_point p JOIN tour_departure d ON d.id=p.departure_id
                WHERE d.id=? AND d.operational_status='OPEN' AND d.departure_date>? AND (? IS NULL OR d.tour_id=?)
                """, departureId, LocalDateTime.now(BUSINESS_ZONE), tourId, tourId).stream().findFirst().orElse(null);
    }

    private static List<DepartureMeetingPoint> query(JdbcTemplate jdbc, String sql, Object... args) {
        return jdbc.query(sql, (rs, index) -> {
            String time = rs.getString("arrival_time");
            return new DepartureMeetingPoint(rs.getString("address"), rs.getString("landmark"),
                    time == null || time.isBlank() ? null : LocalTime.parse(time.strip()), rs.getString("directions_url"));
        }, args);
    }
}
