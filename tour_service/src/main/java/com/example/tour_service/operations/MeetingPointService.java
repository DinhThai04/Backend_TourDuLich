package com.example.tour_service.operations;

import org.springframework.stereotype.Service;
import java.net.URI;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.List;
import java.util.ArrayList;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.web.server.ResponseStatusException;

@Service
public class MeetingPointService {
	private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");
	private final JdbcTemplate jdbc;
	private final OperationAudit audit;

	public MeetingPointService(JdbcTemplate jdbc, OperationAudit audit) {
		this.jdbc = jdbc;
		this.audit = audit;
	}

    @Transactional(readOnly = true)
	public DepartureMeetingPoint get(int departureId, Integer tourId) {
        var point = publicRead(tourId) ? DepartureMeetingPoint.readPublic(jdbc, departureId, tourId)
                : DepartureMeetingPoint.read(jdbc, departureId);
        return point;
    }

    @Transactional(readOnly = true)
    public List<DepartureMeetingPoint> options(int departureId,
            Integer tourId) {
        var points = publicRead(tourId) ? DepartureMeetingPoint.publicOptions(jdbc, departureId, tourId)
                : DepartureMeetingPoint.options(jdbc, departureId);
        return points;
    }

    @Transactional
    public DepartureMeetingPoint put(int departureId, DepartureMeetingPoint input) {
        lock(departureId);
        var point = normalize(input);
        var before = DepartureMeetingPoint.options(jdbc, departureId);
        var after = new ArrayList<>(before);
        if (!after.isEmpty()) after.removeFirst();
        if (point != null) after.addFirst(point);
        var saved = save(departureId, before, after);
        return saved.isEmpty() ? null : saved.getFirst();
    }

    @Transactional
    public List<DepartureMeetingPoint> replace(int departureId,
            List<DepartureMeetingPoint> input) {
        if (input.size() > 3) throw invalid("Mỗi chuyến nhận tối đa ba điểm tập trung.");
        var points = new ArrayList<DepartureMeetingPoint>();
        for (var point : input) {
            if (point == null) throw invalid("Vui lòng nhập địa chỉ cho mọi điểm tập trung.");
            var normalized = normalize(point);
            if (normalized == null || normalized.arrivalTime() == null)
                throw invalid("Vui lòng nhập địa chỉ và giờ có mặt cho mọi điểm tập trung.");
            if (points.stream().anyMatch(saved -> saved.address().equalsIgnoreCase(normalized.address())
                    && saved.arrivalTime().equals(normalized.arrivalTime())))
                throw invalid("Điểm tập trung bị trùng địa chỉ và giờ có mặt.");
            points.add(normalized);
        }
        lock(departureId);
        return save(departureId, DepartureMeetingPoint.options(jdbc, departureId), points);
    }

    @Transactional(readOnly = true)
    public void requireValidPickup(int departureId) {
        DepartureMeetingPoint.requireValidPickup(jdbc, departureId);
    }

    private void lock(int departureId) {
        if (jdbc.queryForList("SELECT id FROM tour_departure WHERE id=? FOR UPDATE", departureId).isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy lịch khởi hành");
    }

    private boolean publicRead(Integer tourId) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return tourId != null || auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken;
    }

    private DepartureMeetingPoint normalize(DepartureMeetingPoint input) {
        String address = text(input.address(), 300);
        String landmark = text(input.landmark(), 300);
        String url = text(input.directionsUrl(), 1000);
        if (url != null && !safeDirections(url)) throw invalid("Liên kết chỉ đường phải dùng HTTPS");
        if (address == null && (landmark != null || url != null || input.arrivalTime() != null))
            throw invalid("Nhập địa chỉ điểm tập trung trước");
        return address == null ? null : new DepartureMeetingPoint(address, landmark, input.arrivalTime(), url);
    }

    private List<DepartureMeetingPoint> save(int id, List<DepartureMeetingPoint> before,
            List<DepartureMeetingPoint> requested) {
        var after = requested.stream().map(point -> before.stream()
                .filter(old -> old.withRevision(null).equals(point.withRevision(null))).findFirst()
                .orElseGet(() -> point.withRevision(java.util.UUID.randomUUID().toString()))).toList();
        if (Objects.equals(before, after)) return before;
        jdbc.update("DELETE FROM departure_extra_meeting_point WHERE departure_id=?", id);
        jdbc.update("DELETE FROM departure_meeting_point WHERE departure_id=?", id);
        for (int index = 0; index < after.size(); index++) {
            var point = after.get(index);
            String time = point.arrivalTime() == null ? null : CLOCK.format(point.arrivalTime());
            if (index == 0) jdbc.update("""
                    INSERT INTO departure_meeting_point(departure_id,address,landmark,arrival_time,directions_url,revision_key,updated_at)
                    VALUES (?,?,?,?,?,?,CURRENT_TIMESTAMP(6))
                    """, id, point.address(), point.landmark(), time, point.directionsUrl(), point.revisionKey());
            else jdbc.update("""
                    INSERT INTO departure_extra_meeting_point(departure_id,stop_order,address,landmark,arrival_time,directions_url,revision_key)
                    VALUES (?,?,?,?,?,?,?)
                    """, id, index + 1, point.address(), point.landmark(), time, point.directionsUrl(), point.revisionKey());
        }
        audit.record("MEETING_POINT", id, before.toString(), after.toString());
        return after;
    }

    private ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
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