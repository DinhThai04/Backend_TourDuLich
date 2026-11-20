package com.example.tour_service.operations;

import com.example.tour_service.dto.response.ApiResponse;
import java.net.URI;
import java.time.LocalTime;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/tour-departures/{departureId}/meeting-point")
public class MeetingPointController {
    public record MeetingPoint(String address, String landmark, LocalTime arrivalTime, String directionsUrl) {}
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");
    private final JdbcTemplate jdbc;
    private final OperationAudit audit;

    public MeetingPointController(JdbcTemplate jdbc, OperationAudit audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @GetMapping
    public ApiResponse<MeetingPoint> get(@PathVariable int departureId, @RequestParam(required = false) Integer tourId) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        boolean anonymous = auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken;
        var point = tourId != null || anonymous
                ? publicPoint(departureId, tourId) : read(departureId);
        return ApiResponse.<MeetingPoint>builder().result(point).build();
    }

    @PutMapping
    @Transactional
    public ApiResponse<MeetingPoint> put(@PathVariable int departureId, @RequestBody MeetingPoint input) {
        if (jdbc.queryForList("SELECT id FROM tour_departure WHERE id=? FOR UPDATE", departureId).isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy lịch khởi hành");
        String address = text(input.address(), 300);
        String landmark = text(input.landmark(), 300);
        String url = text(input.directionsUrl(), 1000);
        if (url != null && !safeDirections(url))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Liên kết chỉ đường phải dùng HTTPS");
        if (address == null && (landmark != null || url != null || input.arrivalTime() != null))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nhập địa chỉ điểm tập trung trước");
        var before = read(departureId);
        var after = address == null ? null : new MeetingPoint(address, landmark, input.arrivalTime(), url);
        if (!Objects.equals(before, after)) {
            if (after == null) jdbc.update("DELETE FROM departure_meeting_point WHERE departure_id=?", departureId);
            else {
                String arrival = after.arrivalTime() == null ? null : CLOCK.format(after.arrivalTime());
                if (before == null) jdbc.update("""
                        INSERT INTO departure_meeting_point(departure_id,address,landmark,arrival_time,directions_url)
                        VALUES (?,?,?,?,?)
                        """, departureId, address, landmark, arrival, url);
                else jdbc.update("""
                        UPDATE departure_meeting_point SET address=?,landmark=?,arrival_time=?,directions_url=? WHERE departure_id=?
                        """, address, landmark, arrival, url, departureId);
            }
            audit.record("MEETING_POINT", departureId, Objects.toString(before, null), Objects.toString(after, null));
        }
        return ApiResponse.<MeetingPoint>builder().result(after).build();
    }

    private MeetingPoint publicPoint(int departureId, Integer tourId) {
        String sql = """
                SELECT p.address,p.landmark,p.arrival_time,p.directions_url
                FROM departure_meeting_point p JOIN tour_departure d ON d.id=p.departure_id
                WHERE p.departure_id=? AND d.operational_status='OPEN' AND d.departure_date>?
                """;
        var now = LocalDateTime.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        return tourId == null ? query(sql, departureId, now)
                : query(sql + " AND d.tour_id=?", departureId, now, tourId);
    }

    private MeetingPoint read(int departureId) {
        return query("SELECT address,landmark,arrival_time,directions_url FROM departure_meeting_point WHERE departure_id=?", departureId);
    }

    private MeetingPoint query(String sql, Object... parameters) {
        return jdbc.query(sql, (rs, index) -> new MeetingPoint(rs.getString("address"), rs.getString("landmark"),
                rs.getObject("arrival_time", LocalTime.class), rs.getString("directions_url")), parameters)
                .stream().findFirst().orElse(null);
    }

    private String text(String value, int maxLength) {
        if (value == null || value.isBlank()) return null;
        String result = value.strip();
        if (result.codePointCount(0, result.length()) > maxLength)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Thông tin điểm tập trung quá dài");
        return result;
    }

    private boolean safeDirections(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}
