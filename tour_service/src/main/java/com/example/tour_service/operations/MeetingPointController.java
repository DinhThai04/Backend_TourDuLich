package com.example.tour_service.operations;

import com.example.tour_service.dto.response.ApiResponse;
import com.example.tour_service.repository.httpclient.TourInternalClient;
import java.net.URI;
import java.time.LocalTime;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
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
	private final TourInternalClient tourClient;

	public MeetingPointController(JdbcTemplate jdbc, OperationAudit audit, TourInternalClient tourClient) {
		this.jdbc = jdbc;
		this.audit = audit;
		this.tourClient = tourClient;
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
		// Get departure info via Tour internal API
		Map<String, Object> departure;
		try {
			departure = tourClient.getDeparture(departureId);
		} catch (Exception e) {
			return null;
		}
		if (departure == null || departure.isEmpty()) return null;
		
		String operationalStatus = (String) departure.get("operational_status");
		Object depDate = departure.get("departure_date");
		LocalDateTime departureDate;
		if (depDate instanceof LocalDateTime ldt) {
			departureDate = ldt;
		} else if (depDate instanceof java.sql.Timestamp ts) {
			departureDate = ts.toLocalDateTime();
		} else if (depDate instanceof String s && !s.isBlank()) {
			departureDate = LocalDateTime.parse(s.contains(" ") && !s.contains("T") ? s.replace(' ', 'T') : s);
		} else {
			return null;
		}
		Integer departureTourId = (Integer) departure.get("tour_id");
		
		var now = LocalDateTime.now(ZoneId.of("Asia/Ho_Chi_Minh"));
		if (!"OPEN".equals(operationalStatus) || !departureDate.isAfter(now)) return null;
		if (tourId != null && !tourId.equals(departureTourId)) return null;
		
		return query("SELECT address,landmark,arrival_time,directions_url FROM departure_meeting_point WHERE departure_id=?", departureId);
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