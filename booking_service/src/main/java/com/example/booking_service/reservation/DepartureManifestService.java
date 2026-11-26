package com.example.booking_service.reservation;

import com.example.booking_service.repository.httpclient.TourInternalClient;
import com.example.booking_service.repository.httpclient.AuthInternalClient;
import feign.FeignException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class DepartureManifestService {
    public enum Attendance { EXPECTED, BOARDED, NO_SHOW, RETURNED }
    public enum Phase { SCHEDULED, BOARDING, IN_PROGRESS, AWAITING_CLOSEOUT, COMPLETED, CANCELLED, SUSPENDED }

    public record Passenger(int customerId, int bookingId, String name, Attendance status) {}
    public record Manifest(Phase phase, LocalDateTime completedAt, Integer completedBy, String closeoutNote,
                           List<Passenger> passengers, Integer guideAccountId) {}
    private record Closeout(LocalDateTime at, Integer actor, String note) {}
    private record Schedule(LocalDateTime departure, LocalDateTime returns, String status) {}

    private final JdbcTemplate jdbc;
    private final BookingAccess access;
    private final TourInternalClient tours;
    private final Clock clock;
    private final AuthInternalClient accounts;

    public DepartureManifestService(JdbcTemplate jdbc, BookingAccess access, TourInternalClient tours, Clock clock, AuthInternalClient accounts) {
        this.jdbc = jdbc;
        this.access = access;
        this.tours = tours;
        this.clock = clock;
        this.accounts = accounts;
    }

    @Transactional(readOnly = true)
    public Manifest get(int departureId) {
        requireOperator(departureId);
        var schedule = schedule(departureId);
        return manifest(departureId, schedule, closeout(departureId, false));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Manifest mark(int departureId, int customerId, Attendance status) {
        int actor = requireOperator(departureId);
        var schedule = schedule(departureId);
        var closeout = lock(departureId);
        requireOperator(departureId);
        var current = manifest(departureId, schedule, closeout);
        if (current.phase() == Phase.COMPLETED || current.phase() == Phase.CANCELLED
                || current.phase() == Phase.SUSPENDED || current.phase() == Phase.SCHEDULED) {
            throw conflict("Chuyến chưa mở điểm danh hoặc đã khóa vận hành.");
        }
        var passenger = current.passengers().stream().filter(p -> p.customerId() == customerId)
                .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Hành khách không thuộc đơn đã thanh toán của chuyến."));
        if (status == passenger.status()) return current;
        if (status == null || status == Attendance.EXPECTED
                || (status == Attendance.NO_SHOW && current.phase() == Phase.BOARDING)
                || (status == Attendance.RETURNED && (passenger.status() != Attendance.BOARDED
                    || current.phase() == Phase.BOARDING))
                || (passenger.status() == Attendance.RETURNED && status != Attendance.BOARDED)) {
            throw conflict("Không thể cập nhật hành khách sang trạng thái đã chọn.");
        }
        jdbc.update("""
                INSERT INTO departure_attendance(departure_id,customer_id,status,updated_at,updated_by)
                VALUES (?,?,?,?,?)
                ON DUPLICATE KEY UPDATE status=VALUES(status),updated_at=VALUES(updated_at),updated_by=VALUES(updated_by)
                """, departureId, customerId, status.name(), LocalDateTime.now(clock), actor);
        return manifest(departureId, schedule, closeout);
    }

    @Transactional
    public Manifest complete(int departureId, String note) {
        int actor = requireAdmin();
        var schedule = schedule(departureId);
        var current = manifest(departureId, schedule, lock(departureId));
        if (current.phase() == Phase.COMPLETED) return current;
        if (current.phase() != Phase.AWAITING_CLOSEOUT
                || current.passengers().stream().noneMatch(p -> p.status() == Attendance.RETURNED)
                || current.passengers().stream().anyMatch(p -> p.status() == Attendance.EXPECTED || p.status() == Attendance.BOARDED)) {
            throw conflict("Chỉ chốt sau giờ về, khi đã xác nhận ít nhất một khách trở về và đã xử lý toàn bộ danh sách.");
        }
        var closeout = new Closeout(LocalDateTime.now(clock).withNano(0), actor, note.strip());
        jdbc.update("UPDATE departure_manifest SET completed_at=?,completed_by=?,closeout_note=? WHERE departure_id=?",
                closeout.at(), actor, closeout.note(), departureId);
        return manifest(departureId, schedule, closeout);
    }

    @Transactional(readOnly = true)
    public List<Integer> assignedDepartures() {
        return jdbc.queryForList("""
                SELECT departure_id FROM departure_guide_assignment
                WHERE guide_account_id=? ORDER BY assigned_at DESC, departure_id DESC
                """, Integer.class, access.accountId());
    }

    @Transactional
    public Manifest assign(int departureId, Integer guideId) {
        int admin = requireAdmin();
        var schedule = schedule(departureId);
        if (guideId != null) {
            try {
                var status = accounts.getUserStatus(guideId.longValue());
                if (status == null || !"ACTIVE".equals(status.get("status"))) {
                    throw conflict("Tài khoản hướng dẫn viên phải đang hoạt động.");
                }
            } catch (FeignException.NotFound missing) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy tài khoản hướng dẫn viên.");
            } catch (FeignException unavailable) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Chưa xác minh được tài khoản.");
            }
        }
        var closeout = lock(departureId);
        if (guideId == null) {
            jdbc.update("DELETE FROM departure_guide_assignment WHERE departure_id=?", departureId);
        } else {
            if (phase(schedule, closeout) == Phase.COMPLETED || phase(schedule, closeout) == Phase.CANCELLED) {
                throw conflict("Không thể phân công chuyến đã chốt hoặc đã hủy.");
            }
            jdbc.update("""
                    INSERT INTO departure_guide_assignment(departure_id,guide_account_id,assigned_by,assigned_at)
                    VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE guide_account_id=VALUES(guide_account_id),
                    assigned_by=VALUES(assigned_by),assigned_at=VALUES(assigned_at)
                    """, departureId, guideId, admin, LocalDateTime.now(clock));
        }
        return manifest(departureId, schedule, closeout);
    }

    private Integer guide(int departureId) {
        return jdbc.queryForList("SELECT guide_account_id FROM departure_guide_assignment WHERE departure_id=?",
                Integer.class, departureId).stream().findFirst().orElse(null);
    }

    private int requireOperator(int departureId) {
        int actor = access.accountId();
        if (!access.admin() && !Integer.valueOf(actor).equals(guide(departureId))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn chưa được phân công vận hành chuyến này.");
        }
        return actor;
    }

    private int requireAdmin() {
        int actor = access.accountId();
        if (!access.admin()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Chỉ quản trị viên được vận hành chuyến.");
        return actor;
    }

    private Schedule schedule(int departureId) {
        try {
            var departure = tours.getDeparture(departureId);
            if (departure == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy lịch khởi hành.");
            var start = BookingMailEvents.date(departure.get("departure_date"));
            var end = BookingMailEvents.date(departure.get("return_date"));
            if (start == null || end == null || end.isBefore(start)) {
                throw conflict("Lịch khởi hành chưa có mốc giờ đi và về hợp lệ.");
            }
            return new Schedule(start, end, String.valueOf(departure.get("operational_status")));
        } catch (FeignException.NotFound missing) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy lịch khởi hành.");
        } catch (FeignException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Chưa tải được lịch khởi hành.");
        }
    }

    private Closeout lock(int departureId) {
        jdbc.update("""
                INSERT INTO departure_manifest(departure_id) VALUES (?)
                ON DUPLICATE KEY UPDATE departure_id=departure_id
                """, departureId);
        return closeout(departureId, true);
    }

    private Closeout closeout(int departureId, boolean lock) {
        return jdbc.query("""
                SELECT completed_at,completed_by,closeout_note FROM departure_manifest WHERE departure_id=?
                """ + (lock ? " FOR UPDATE" : ""), (rs, row) -> new Closeout(
                rs.getObject("completed_at", LocalDateTime.class),
                rs.getObject("completed_by", Integer.class), rs.getString("closeout_note")), departureId)
                .stream().findFirst().orElse(new Closeout(null, null, null));
    }

    private Manifest manifest(int id, Schedule schedule, Closeout closeout) {
        var passengers = jdbc.query("""
                SELECT c.id, b.id AS booking_id, c.full_name, a.status
                FROM booking b
                JOIN booking_reservation r ON r.booking_id=b.id AND r.departure_id=b.tour_departure_id AND r.status='PAID'
                JOIN customer c ON c.booking_id=b.id
                LEFT JOIN departure_attendance a ON a.departure_id=b.tour_departure_id AND a.customer_id=c.id
                WHERE b.tour_departure_id=? AND b.status='CONFIRMED'
                ORDER BY b.id, c.id
                """, (rs, row) -> new Passenger(rs.getInt("id"), rs.getInt("booking_id"), rs.getString("full_name"),
                rs.getString("status") == null ? Attendance.EXPECTED : Attendance.valueOf(rs.getString("status"))), id);
        return new Manifest(phase(schedule, closeout), closeout.at(), closeout.actor(), closeout.note(), passengers, access.admin() ? guide(id) : null);
    }

    private Phase phase(Schedule schedule, Closeout closeout) {
        if (closeout.at() != null) return Phase.COMPLETED;
        if ("CANCELLED".equals(schedule.status())) return Phase.CANCELLED;
        if ("SUSPENDED".equals(schedule.status())) return Phase.SUSPENDED;
        var now = LocalDateTime.now(clock);
        if (!now.isBefore(schedule.returns())) return Phase.AWAITING_CLOSEOUT;
        if (!now.isBefore(schedule.departure())) return Phase.IN_PROGRESS;
        return now.isBefore(schedule.departure().minusMinutes(90)) ? Phase.SCHEDULED : Phase.BOARDING;
    }

    private static ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }
}
