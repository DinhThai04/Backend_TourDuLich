package com.example.booking_service.reservation;

import com.example.booking_service.repository.httpclient.TourInternalClient;
import com.example.booking_service.repository.httpclient.AuthInternalClient;
import com.example.booking_service.operations.AdminAuditEvents;
import com.example.booking_service.repository.httpclient.InvoiceInternalClient;
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
import java.util.Map;

@Service
public class DepartureManifestService {
    public enum Attendance { EXPECTED, BOARDED, NO_SHOW, RETURNED, SEPARATED, UNVERIFIED_RETURN }
    public enum Phase { SCHEDULED, BOARDING, IN_PROGRESS, AWAITING_CLOSEOUT, COMPLETED, CANCELLED, INCIDENT, OVERDUE_UNCONFIRMED }

    public record Passenger(int customerId, int bookingId, String name, Attendance status, String note, String meetingPointName,
                            String pickupTime, int accountId, String guardianNote, BookingPickupService.PickupStatus pickupStatus) {}
    public record Manifest(Phase phase, LocalDateTime completedAt, Integer completedBy, String closeoutNote,
                           List<Passenger> passengers, Integer guideAccountId, String outcome,
                           LocalDateTime actualDepartureAt, Integer confirmedBy) {}
    public record Report(int guideId, String status, String summary, String incidentNote, String handoverNote,
                         LocalDateTime submittedAt, Integer reviewedBy, LocalDateTime reviewedAt, String adminComment) {}
    public enum Progress { NOT_STARTED, IN_PROGRESS, COMPLETED, DELAYED, SKIPPED }
    public record Checkpoint(int id, int day, String title, Progress status, LocalDateTime actualTime, String note) {}
    private record Closeout(LocalDateTime at, Integer actor, String note, String outcome, LocalDateTime actualDepartureAt, Integer confirmedBy) {}
    private record Schedule(LocalDateTime departure, LocalDateTime returns, String status, String code,
                            List<BookingPickupService.Option> pickups) {}

    private final JdbcTemplate jdbc;
    private final BookingAccess access;
    private final TourInternalClient tours;
    private final Clock clock;
    private final AuthInternalClient accounts;
    private final AdminAuditEvents auditEvents;
    private final InvoiceInternalClient invoices;

    public DepartureManifestService(JdbcTemplate jdbc, BookingAccess access, TourInternalClient tours, Clock clock, AuthInternalClient accounts,
                                    AdminAuditEvents auditEvents, InvoiceInternalClient invoices) {
        this.jdbc = jdbc;
        this.access = access;
        this.tours = tours;
        this.clock = clock;
        this.accounts = accounts;
        this.auditEvents = auditEvents;
        this.invoices = invoices;
    }

    @Transactional(readOnly = true)
    public Manifest get(int departureId) {
        requireOperator(departureId);
        var schedule = schedule(departureId);
        return manifest(departureId, schedule, closeout(departureId, false));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Manifest confirmDeparture(int id, LocalDateTime actual, String reason) {
        requireOperator(id);
        var state = lock(id);
        int actor = requireOperator(id);
        var schedule = schedule(id);
        if (state.actualDepartureAt() != null) return manifest(id, schedule, state);
        var now = LocalDateTime.now(clock).withNano(0);
        if (!"OPEN".equals(schedule.status()) || state.at() != null || now.isBefore(schedule.departure()))
            throw conflict("Chỉ xác nhận xuất phát cho chuyến đang mở đã đến giờ khởi hành.");
        boolean late = actual != null || !now.isBefore(schedule.departure().plusMinutes(15))
                || !now.isBefore(schedule.returns());
        String explanation = reason == null ? null : reason.strip();
        if (late && (actual == null || explanation == null || explanation.isBlank()))
            throw conflict("Nhập giờ xuất phát thực tế và lý do xác nhận muộn.");
        var recorded = actual == null ? now : actual.withNano(0);
        if (recorded.isBefore(schedule.departure().minusMinutes(90)) || recorded.isAfter(now)
                || !recorded.isBefore(schedule.returns()))
            throw conflict("Giờ xuất phát thực tế phải từ lúc mở điểm danh đến trước giờ về và không ở tương lai.");
        jdbc.update("UPDATE departure_manifest SET actual_departure_at=?,confirmed_by=? WHERE departure_id=?",
                recorded, actor, id);
        auditEvents.record(actor, "DEPARTURE_CONFIRMED", "DEPARTURE", id, departureLabel(id, schedule),
                "Xác nhận xuất phát", explanation, Map.of("status", "OVERDUE_UNCONFIRMED"),
                Map.of("status", "IN_PROGRESS", "actualDepartureAt", recorded.toString()));
        return manifest(id, schedule, closeout(id, false));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Manifest mark(int departureId, int customerId, Attendance status) {
        return mark(departureId, customerId, status, null);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Manifest mark(int departureId, int customerId, Attendance status, String note) {
        int actor = requireOperator(departureId);
        var schedule = schedule(departureId);
        var closeout = lock(departureId);
        requireOperator(departureId);
        var current = manifest(departureId, schedule, closeout);
        if (current.phase() == Phase.COMPLETED || current.phase() == Phase.CANCELLED
                || current.phase() == Phase.INCIDENT || current.phase() == Phase.OVERDUE_UNCONFIRMED
                || current.phase() == Phase.SCHEDULED) {
            throw conflict("Chuyến chưa mở điểm danh hoặc đã khóa vận hành.");
        }
        var passenger = current.passengers().stream().filter(p -> p.customerId() == customerId)
                .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Hành khách không thuộc đơn đã thanh toán của chuyến."));
        if (status == null) throw conflict("Chọn trạng thái điểm danh.");
        String detail = note == null ? null : note.strip();
        boolean exceptional = status == Attendance.SEPARATED || status == Attendance.UNVERIFIED_RETURN;
        if (exceptional && (detail == null || detail.isBlank())) throw conflict("Cần ghi lý do và cách xử lý ngoại lệ.");
        if (status == passenger.status() && java.util.Objects.equals(detail, passenger.note())) return current;
        var report = reportRow(departureId);
        if (report != null && List.of("SUBMITTED", "APPROVED").contains(report.status()))
            throw conflict("Báo cáo đang chờ duyệt hoặc đã duyệt. Cần yêu cầu bổ sung trước khi sửa điểm danh.");
        boolean afterDeparture = current.phase() != Phase.BOARDING;
        boolean valid = switch (status) {
            case BOARDED -> passenger.status() == Attendance.EXPECTED || access.admin();
            case NO_SHOW -> afterDeparture && (passenger.status() == Attendance.EXPECTED || access.admin());
            case RETURNED, SEPARATED, UNVERIFIED_RETURN -> afterDeparture && (passenger.status() == Attendance.BOARDED
                    || access.admin() && List.of(Attendance.RETURNED, Attendance.SEPARATED,
                            Attendance.UNVERIFIED_RETURN).contains(passenger.status()));
            default -> false;
        };
        if (!valid) throw conflict("Không thể cập nhật hành khách sang trạng thái đã chọn.");
        jdbc.update("""
                INSERT INTO departure_attendance(departure_id,customer_id,status,updated_at,updated_by,note)
                VALUES (?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE status=VALUES(status),updated_at=VALUES(updated_at),updated_by=VALUES(updated_by),note=VALUES(note)
                """, departureId, customerId, status.name(), LocalDateTime.now(clock), actor, detail);
        audit(departureId, actor, "ATTENDANCE", "#" + customerId + " " + passenger.status() + " → " + status
                + (detail == null ? "" : " · " + detail));
        return manifest(departureId, schedule, closeout);
    }

    @Transactional
    public Manifest complete(int departureId, String note) {
        return complete(departureId, note, false);
    }

    @Transactional
    public Manifest complete(int departureId, String note, boolean override) {
        int actor = requireAdmin();
        var schedule = schedule(departureId);
        var current = manifest(departureId, schedule, lock(departureId));
        if (current.phase() == Phase.COMPLETED) return current;
        if (current.phase() != Phase.AWAITING_CLOSEOUT || !terminal(current))
            throw conflict("Chỉ chốt sau giờ về và khi đã xử lý toàn bộ danh sách hành khách.");
        var report = reportRow(departureId);
        if (report == null || !"APPROVED".equals(report.status())) {
            if (!override || note == null || note.isBlank())
                throw conflict("Cần báo cáo đã duyệt hoặc ghi rõ lý do chốt ngoại lệ.");
            audit(departureId, actor, "CLOSE_OVERRIDE", note);
        }
        String outcome = current.passengers().isEmpty() ? "COMPLETED_NO_PASSENGERS"
                : current.passengers().stream().allMatch(p -> p.status() == Attendance.NO_SHOW) ? "COMPLETED_NO_SHOW" : "COMPLETED";
        var closeout = new Closeout(LocalDateTime.now(clock).withNano(0), actor, note.strip(), outcome,
                current.actualDepartureAt(), current.confirmedBy());
        jdbc.update("UPDATE departure_manifest SET completed_at=?,completed_by=?,closeout_note=?,outcome=? WHERE departure_id=?",
                closeout.at(), actor, closeout.note(), outcome, departureId);
        auditEvents.record(actor, override ? "DEPARTURE_FORCE_COMPLETED" : "DEPARTURE_COMPLETED",
                "DEPARTURE", departureId, departureLabel(departureId, schedule), "Chốt hoàn thành chuyến",
                closeout.note(), Map.of("status", current.phase()), Map.of("status", outcome));
        return manifest(departureId, schedule, closeout);
    }

    @Transactional(readOnly = true)
    public List<Integer> assignedDepartures() {
        return jdbc.queryForList("""
                SELECT departure_id FROM departure_guide_assignment
                WHERE guide_account_id=? ORDER BY assigned_at DESC, departure_id DESC
                """, Integer.class, access.accountId());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Manifest assign(int departureId, Integer guideId) {
        return assign(departureId, guideId, false, null);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Manifest assign(int departureId, Integer guideId, boolean override, String reason) {
        int admin = requireAdmin();
        var schedule = schedule(departureId);
        if (guideId != null) {
            try {
                var status = accounts.getUserStatus(guideId.longValue());
                if (status == null || !"ACTIVE".equals(status.get("status"))) {
                    throw conflict("Tài khoản hướng dẫn viên phải đang hoạt động.");
                }
                if (!Boolean.TRUE.equals(status.get("tourGuide"))) {
                    throw conflict("Tài khoản chưa có vai trò Hướng dẫn viên.");
                }
            } catch (FeignException.NotFound missing) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy tài khoản hướng dẫn viên.");
            } catch (FeignException unavailable) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Chưa xác minh được tài khoản.");
            }
        }
        var closeout = lock(departureId);
        Integer previous = guide(departureId);
        if (java.util.Objects.equals(previous, guideId)) return manifest(departureId, schedule, closeout);
        if (closeout.at() != null) throw conflict("Chuyến đã chốt.");
        Integer overlap = null;
        if (guideId == null) {
            jdbc.update("DELETE FROM departure_guide_assignment WHERE departure_id=?", departureId);
        } else {
            if (phase(schedule, closeout) == Phase.COMPLETED || phase(schedule, closeout) == Phase.CANCELLED) {
                throw conflict("Không thể phân công chuyến đã chốt hoặc đã hủy.");
            }
            jdbc.update("INSERT INTO departure_guide_lock(guide_id) VALUES(?) ON DUPLICATE KEY UPDATE guide_id=guide_id", guideId);
            jdbc.queryForObject("SELECT guide_id FROM departure_guide_lock WHERE guide_id=? FOR UPDATE", Integer.class, guideId);
            overlap = overlap(departureId, guideId, schedule, new java.util.HashMap<>());
            if (overlap != null && (!override || reason == null || reason.isBlank()))
                throw conflict("GUIDE_SCHEDULE_CONFLICT: Trùng lịch #" + overlap);
            if (overlap != null) audit(departureId, admin, "GUIDE_OVERRIDE", "Lịch #" + overlap + " · " + reason.strip());
            jdbc.update("""
                    INSERT INTO departure_guide_assignment(departure_id,guide_account_id,assigned_by,assigned_at)
                    VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE guide_account_id=VALUES(guide_account_id),
                    assigned_by=VALUES(assigned_by),assigned_at=VALUES(assigned_at)
                    """, departureId, guideId, admin, LocalDateTime.now(clock));
        }
        audit(departureId, admin, "GUIDE_ASSIGNMENT", "Tài khoản " + previous + " → " + guideId);
        String action = guideId == null ? "GUIDE_UNASSIGNED" : overlap == null ? "GUIDE_ASSIGNED" : "GUIDE_CONFLICT_OVERRIDDEN";
        auditEvents.record(admin, action, "DEPARTURE", departureId, departureLabel(departureId, schedule),
                guideId == null ? "Gỡ phân công hướng dẫn viên" : "Phân công hướng dẫn viên",
                overlap == null ? null : reason.strip(), previous == null ? Map.of() : Map.of("guideAccountId", previous),
                guideId == null ? Map.of() : Map.of("guideAccountId", guideId));
        return manifest(departureId, schedule, closeout);
    }

    @Transactional(readOnly = true)
    public java.util.Map<Integer, Integer> guideConflicts(int departureId, List<Integer> ids) {
        requireAdmin();
        if (ids == null || ids.size() > 30 || ids.stream().anyMatch(id -> id == null || id < 1))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chọn tối đa 30 hướng dẫn viên.");
        var target = schedule(departureId);
        var result = new java.util.LinkedHashMap<Integer, Integer>();
        var last = new java.util.HashMap<Integer, Schedule>();
        for (int id : ids.stream().distinct().toList()) {
            var conflict = overlap(departureId, id, target, last);
            if (conflict != null) result.put(id, conflict);
        }
        return result;
    }

    private Integer overlap(int departureId, int guideId, Schedule target, java.util.Map<Integer, Schedule> schedules) {
        var otherIds = jdbc.queryForList("""
                SELECT a.departure_id FROM departure_guide_assignment a JOIN departure_manifest m ON m.departure_id=a.departure_id
                WHERE a.guide_account_id=? AND a.departure_id<>? AND m.completed_at IS NULL
                """, Integer.class, guideId, departureId);
        for (int otherId : otherIds) {
            Schedule other;
            try {
                other = schedules.computeIfAbsent(otherId, this::schedule);
            } catch (ResponseStatusException error) {
                if (error.getStatusCode().equals(HttpStatus.NOT_FOUND)) continue;
                throw error;
            }
            if (!"CANCELLED".equals(other.status()) && target.departure().isBefore(other.returns())
                    && target.returns().isAfter(other.departure())) return otherId;
        }
        return null;
    }

    @Transactional(readOnly = true)
    public java.util.Map<Integer, String> contacts(int departureId) {
        requireOperator(departureId);
        var manifest = get(departureId);
        var ids = manifest.passengers().stream().map(Passenger::accountId).distinct().toList();
        var result = new java.util.LinkedHashMap<Integer, String>();
        for (int offset = 0; offset < ids.size(); offset += 100) {
            var batch = ids.subList(offset, Math.min(ids.size(), offset + 100)).stream().map(Integer::longValue).toList();
            try {
                for (var row : accounts.getUsersBatch(batch)) {
                    if (row.get("id") != null && row.get("phone") instanceof String phone && !phone.isBlank())
                        result.put(Integer.parseInt(row.get("id").toString()), phone);
                }
            } catch (FeignException unavailable) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Chưa tải được số điện thoại người đặt.");
            }
        }
        return result;
    }

    @Transactional(readOnly = true)
    public Report report(int departureId) {
        requireOperator(departureId);
        return reportRow(departureId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Report saveReport(int id, boolean submit, String summary, String incident, String handover) {
        int actor = requireOperator(id);
        var schedule = schedule(id);
        var closeout = lock(id);
        requireAssignedGuide(id, actor);
        var current = manifest(id, schedule, closeout);
        if (current.phase() != Phase.IN_PROGRESS && current.phase() != Phase.AWAITING_CLOSEOUT)
            throw conflict("Chỉ lập báo cáo khi chuyến đang đi hoặc chờ chốt.");
        var prior = reportRow(id);
        if (prior != null && List.of("SUBMITTED", "APPROVED").contains(prior.status())) {
            if (submit && "SUBMITTED".equals(prior.status()) && prior.guideId() == actor
                    && java.util.Objects.equals(prior.summary(), summary)
                    && java.util.Objects.equals(prior.incidentNote(), incident)
                    && java.util.Objects.equals(prior.handoverNote(), handover)) return prior;
            throw conflict("Báo cáo đã nộp hoặc đã duyệt.");
        }
        if (submit && (current.phase() != Phase.AWAITING_CLOSEOUT || !terminal(current) || handover == null || handover.isBlank()))
            throw conflict("Cần qua giờ về, xử lý xong danh sách khách và ghi nội dung bàn giao trước khi nộp.");
        jdbc.update("""
                INSERT INTO departure_trip_report(departure_id,guide_id,status,summary,incident_note,handover_note,submitted_at)
                VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE guide_id=VALUES(guide_id),status=VALUES(status),
                summary=VALUES(summary),incident_note=VALUES(incident_note),handover_note=VALUES(handover_note),
                submitted_at=VALUES(submitted_at),reviewed_at=NULL,reviewed_by=NULL
                """, id, actor, submit ? "SUBMITTED" : "DRAFT", summary, incident, handover, submit ? LocalDateTime.now(clock) : null);
        if (submit) audit(id, actor, "REPORT_SUBMITTED", null);
        return reportRow(id);
    }

    @Transactional
    public Report review(int id, boolean approve, String comment) {
        int actor = requireAdmin();
        if (lock(id).at() != null) throw conflict("Chuyến đã chốt.");
        var report = reportRow(id);
        if (report == null || !"SUBMITTED".equals(report.status())) throw conflict("Chưa có báo cáo đang chờ duyệt.");
        if (!approve && (comment == null || comment.isBlank())) throw conflict("Cần lý do yêu cầu bổ sung.");
        String status = approve ? "APPROVED" : "REVISION_REQUESTED";
        jdbc.update("""
                UPDATE departure_trip_report SET status=?,reviewed_by=?,reviewed_at=?,admin_comment=? WHERE departure_id=?
                """, status, actor, LocalDateTime.now(clock), comment, id);
        audit(id, actor, status, comment);
        auditEvents.record(actor, "TRIP_REPORT_REVIEWED", "DEPARTURE", id, "Chuyến #" + id,
                approve ? "Duyệt báo cáo chuyến" : "Yêu cầu bổ sung báo cáo chuyến",
                comment, Map.of("reportStatus", report.status()), Map.of("reportStatus", status));
        return reportRow(id);
    }

    private Report reportRow(int id) {
        return jdbc.query("SELECT * FROM departure_trip_report WHERE departure_id=?", (rs, i) ->
                new Report(rs.getInt("guide_id"),rs.getString("status"),rs.getString("summary"),
                        rs.getString("incident_note"),rs.getString("handover_note"),
                        rs.getObject("submitted_at", LocalDateTime.class),rs.getObject("reviewed_by", Integer.class),
                        rs.getObject("reviewed_at", LocalDateTime.class),rs.getString("admin_comment")), id)
                .stream().findFirst().orElse(null);
    }

    @Transactional(readOnly = true)
    public List<Checkpoint> checkpoints(int id) {
        requireOperator(id);
        return checkpointRows(id);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<Checkpoint> progress(int id, int itineraryId, Progress status, String note) {
        int actor = requireOperator(id);
        var schedule = schedule(id);
        var closeout = lock(id);
        requireAssignedGuide(id, actor);
        var phase = phase(schedule, closeout);
        var report = reportRow(id);
        if ((phase != Phase.IN_PROGRESS && phase != Phase.AWAITING_CLOSEOUT)
                || report != null && List.of("SUBMITTED", "APPROVED").contains(report.status()))
            throw conflict("Chuyến chưa chạy hoặc dữ liệu tiến độ đã khóa.");
        if ((status == Progress.SKIPPED || status == Progress.DELAYED) && (note == null || note.isBlank()))
            throw conflict("Cần ghi lý do trễ hoặc bỏ chặng.");
        if (checkpointRows(id).stream().noneMatch(row -> row.id() == itineraryId))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Chặng không thuộc lịch trình chuyến.");
        var now = LocalDateTime.now(clock);
        jdbc.update("""
                INSERT INTO departure_itinerary_progress(departure_id,itinerary_id,status,actual_time,note,updated_by,updated_at)
                VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE status=VALUES(status),actual_time=VALUES(actual_time),
                note=VALUES(note),updated_by=VALUES(updated_by),updated_at=VALUES(updated_at)
                """, id, itineraryId, status.name(), now, note, actor, now);
        audit(id, actor, "ITINERARY_PROGRESS", "#" + itineraryId + " " + status + (note == null ? "" : " · " + note));
        return checkpointRows(id);
    }

    private List<Checkpoint> checkpointRows(int id) {
        var saved = new java.util.HashMap<Integer, Checkpoint>();
        jdbc.query("SELECT * FROM departure_itinerary_progress WHERE departure_id=?", rs -> {
            int key = rs.getInt("itinerary_id");
            saved.put(key, new Checkpoint(key, 0, "", Progress.valueOf(rs.getString("status")),
                    rs.getObject("actual_time", LocalDateTime.class), rs.getString("note")));
        }, id);
        try {
            return tours.getItinerary(id).stream().map(row -> {
                int key = ((Number) row.get("id")).intValue();
                var change = saved.get(key);
                return new Checkpoint(key, ((Number) row.get("day")).intValue(), String.valueOf(row.get("title")),
                        change == null ? Progress.NOT_STARTED : change.status(), change == null ? null : change.actualTime(),
                        change == null ? null : change.note());
            }).toList();
        } catch (FeignException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Chưa tải được lịch trình chuyến.");
        }
    }

    private void requireAssignedGuide(int id, int actor) {
        if (!Integer.valueOf(actor).equals(guide(id)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Chỉ hướng dẫn viên được phân công có thể cập nhật.");
    }

    private boolean terminal(Manifest manifest) {
        return manifest.passengers().stream().noneMatch(p -> p.status() == Attendance.EXPECTED || p.status() == Attendance.BOARDED);
    }

    private void audit(int departureId, int actor, String action, String note) {
        jdbc.update("INSERT INTO departure_operation_event(departure_id,actor_id,action,note,created_at) VALUES(?,?,?,?,?)",
                departureId, actor, action, note, LocalDateTime.now(clock));
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

    private static String departureLabel(int id, Schedule schedule) {
        return schedule.code() == null || schedule.code().isBlank() ? "Chuyến #" + id : "Chuyến " + schedule.code();
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
            return new Schedule(start, end, String.valueOf(departure.get("operational_status")),
                    (String) departure.get("tour_code"), BookingPickupService.parseOptions(departure));
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
                SELECT completed_at,completed_by,closeout_note,outcome,actual_departure_at,confirmed_by FROM departure_manifest WHERE departure_id=?
                """ + (lock ? " FOR UPDATE" : ""), (rs, row) -> new Closeout(
                rs.getObject("completed_at", LocalDateTime.class),
                rs.getObject("completed_by", Integer.class), rs.getString("closeout_note"), rs.getString("outcome"),
                rs.getObject("actual_departure_at", LocalDateTime.class), rs.getObject("confirmed_by", Integer.class)), departureId)
                .stream().findFirst().orElse(new Closeout(null, null, null, null, null, null));
    }

    private Manifest manifest(int id, Schedule schedule, Closeout closeout) {
        var eligible = paidBookings(id);
        var passengers = jdbc.query("""
                SELECT c.id, b.id AS booking_id, b.account_id, c.full_name, a.status, a.note, p.address AS meeting_point,p.arrival_time,c.guardian_name,c.guardian_phone
                FROM booking b
                JOIN customer c ON c.booking_id=b.id
                LEFT JOIN departure_attendance a ON a.departure_id=b.tour_departure_id AND a.customer_id=c.id
                LEFT JOIN booking_pickup_snapshot p ON p.booking_id=b.id AND p.departure_id=b.tour_departure_id
                WHERE b.tour_departure_id=? AND b.status='CONFIRMED'
                ORDER BY b.id, c.id
                """, (rs, row) -> new Passenger(rs.getInt("id"), rs.getInt("booking_id"), rs.getString("full_name"),
                rs.getString("status") == null ? Attendance.EXPECTED : Attendance.valueOf(rs.getString("status")),
                rs.getString("note"),rs.getString("meeting_point"),rs.getString("arrival_time"),rs.getInt("account_id"),
                rs.getString("guardian_name") == null ? null : rs.getString("guardian_name") + " · " + rs.getString("guardian_phone"),
                null), id);
        var selection = new java.util.HashMap<Integer, BookingPickupService.PickupStatus>();
        var confirmed = passengers.stream().filter(passenger -> eligible.contains(passenger.bookingId())).map(passenger -> {
            var status = selection.computeIfAbsent(passenger.bookingId(),
                    bookingId -> BookingPickupService.selection(jdbc, bookingId, id, schedule.pickups()).status());
            return new Passenger(passenger.customerId(), passenger.bookingId(), passenger.name(), passenger.status(),
                    passenger.note(), status == BookingPickupService.PickupStatus.CONFIRMED ? passenger.meetingPointName() : null,
                    status == BookingPickupService.PickupStatus.CONFIRMED ? passenger.pickupTime() : null,
                    passenger.accountId(), passenger.guardianNote(), status);
        }).toList();
        return new Manifest(phase(schedule, closeout), closeout.at(), closeout.actor(), closeout.note(), confirmed,
                access.admin() ? guide(id) : null, closeout.outcome(), closeout.actualDepartureAt(), closeout.confirmedBy());
    }

    java.util.Set<Integer> paidBookings(int id) {
        var paid = new java.util.HashSet<Integer>();
        var legacy = jdbc.query("""
                SELECT b.id, r.status AS reservation_status, r.departure_id AS reservation_departure_id
                FROM booking b LEFT JOIN booking_reservation r ON r.booking_id=b.id
                WHERE b.tour_departure_id=? AND b.status='CONFIRMED' ORDER BY b.id
                """, (rs, row) -> {
            int booking = rs.getInt("id");
            String status = rs.getString("reservation_status");
            if ("PAID".equals(status) && id == rs.getInt("reservation_departure_id")) {
                paid.add(booking);
                return null;
            }
            return booking;
        }, id).stream().filter(java.util.Objects::nonNull).toList();
        try {
            for (int offset = 0; offset < legacy.size(); offset += 100) {
                var batch = legacy.subList(offset, Math.min(legacy.size(), offset + 100));
                for (var invoice : invoices.getInvoicesBatch(batch)) {
                    if ("PAID".equals(invoice.get("status")) && invoice.get("bookingId") instanceof Number booking
                            && batch.contains(booking.intValue())) paid.add(booking.intValue());
                }
            }
        } catch (FeignException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Chưa đối chiếu được hóa đơn để lập danh sách hành khách.");
        }
        return paid;
    }

    private Phase phase(Schedule schedule, Closeout closeout) {
        if (closeout.at() != null) return Phase.COMPLETED;
        if ("CANCELLED".equals(schedule.status())) return Phase.CANCELLED;
        if ("SUSPENDED".equals(schedule.status())) return Phase.INCIDENT;
        if (!"OPEN".equals(schedule.status())) return Phase.SCHEDULED;
        var now = LocalDateTime.now(clock);
        if (closeout.actualDepartureAt() != null)
            return now.isBefore(schedule.returns()) ? Phase.IN_PROGRESS : Phase.AWAITING_CLOSEOUT;
        if (!now.isBefore(schedule.departure())) return Phase.OVERDUE_UNCONFIRMED;
        return now.isBefore(schedule.departure().minusMinutes(90)) ? Phase.SCHEDULED : Phase.BOARDING;
    }

    private static ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }
}
