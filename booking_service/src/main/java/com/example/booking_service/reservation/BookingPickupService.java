package com.example.booking_service.reservation;

import com.example.booking_service.config.BookingMailOutboxPublisher;
import com.example.booking_service.notification.MailProperties;
import com.example.booking_service.repository.httpclient.AuthInternalClient;
import com.example.booking_service.repository.httpclient.TourInternalClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.springframework.web.util.HtmlUtils.htmlEscape;

@Service
public class BookingPickupService {
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");
    private final JdbcTemplate jdbc;
    private final BookingAccess access;
    private final TourInternalClient tours;
    private final AuthInternalClient auth;
    private final BookingMailOutboxPublisher outbox;
    private final ObjectMapper json;
    private final MailProperties properties;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final DepartureManifestService manifests;
    private final com.example.booking_service.operations.AdminAuditEvents audit;

    public BookingPickupService(JdbcTemplate jdbc, BookingAccess access, TourInternalClient tours,
            AuthInternalClient auth, BookingMailOutboxPublisher outbox, ObjectMapper json,
            MailProperties properties, Clock clock, PlatformTransactionManager manager,
            DepartureManifestService manifests, com.example.booking_service.operations.AdminAuditEvents audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.tours = tours;
        this.auth = auth;
        this.outbox = outbox;
        this.json = json;
        this.properties = properties;
        this.clock = clock;
        this.transaction = new TransactionTemplate(manager);
        this.manifests = manifests;
        this.audit = audit;
    }

    public record Option(String confirmationKey, String address, String landmark, String arrivalTime, String directionsUrl, String revisionKey) {}
    public enum PickupStatus { MISSING, CONFIRMED, NEEDS_RECONFIRMATION }
    public record Choices(List<Option> options, String selectedKey, LocalDateTime chooseUntil, boolean editable,
                          PickupStatus pickupStatus) {}
    record Selection(String key, PickupStatus status) {}
    public record Announcement(int queued, int alreadyQueued, int withoutEmail) {}
    private record Booking(int id, int account, int departure, String status) {}
    private record Departure(String title, LocalDateTime date, LocalDateTime chooseUntil, boolean open, List<Option> options) {}

    public Choices options(int bookingId) {
        var booking = ownedBooking(bookingId);
        var departure = departure(booking.departure());
        return choices(booking, departure, false);
    }

    public Choices select(int bookingId, String confirmationKey) {
        return save(ownedBooking(bookingId), confirmationKey, null);
    }

    public Choices adminOptions(int bookingId) {
        requireAdmin();
        var booking = paidBooking(bookingId);
        return choices(booking, departure(booking.departure()), true);
    }

    public Choices assign(int bookingId, String confirmationKey, String reason) {
        requireAdmin();
        if (reason == null || reason.isBlank() || reason.strip().length() > 500)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nhập lý do chọn điểm đón thay khách.");
        return save(paidBooking(bookingId), confirmationKey, reason.strip());
    }

    private Booking paidBooking(int bookingId) {
        var booking = booking(bookingId, false);
        if (!manifests.paidBookings(booking.departure()).contains(bookingId))
            throw closed("Chỉ chọn điểm đón thay khách cho đơn đã thanh toán.");
        return booking;
    }

    private Choices save(Booking booking, String confirmationKey, String reason) {
        boolean admin = reason != null;
        var departure = departure(booking.departure());
        var point = departure.options().stream().filter(option -> option.confirmationKey().equals(confirmationKey))
                .findFirst().orElseThrow(() -> closed("Danh sách điểm đón đã thay đổi. Vui lòng tải lại."));
        if (!choices(booking, departure, admin).editable()) throw closed("Đã hết thời gian chọn điểm đón.");
        String email = emailFor(booking.account());
        return transaction.execute(tx -> {
            if (admin) {
                jdbc.update("""
                        INSERT INTO departure_manifest(departure_id) VALUES(?)
                        ON DUPLICATE KEY UPDATE departure_id=departure_id
                        """, booking.departure());
                jdbc.queryForObject("SELECT departure_id FROM departure_manifest WHERE departure_id=? FOR UPDATE",
                        Integer.class, booking.departure());
            }
            var current = booking(booking.id(), true);
            var latest = departure(current.departure());
            var before = selection(jdbc, current.id(), current.departure(), latest.options());
            if (!current.equals(booking) || !editable(current, latest, admin)
                    || admin && !canActuallyMeet(latest, point, LocalDateTime.now(clock))
                    || latest.options().stream().noneMatch(option -> point.confirmationKey().equals(option.confirmationKey())))
                throw closed("Đơn hoặc lịch khởi hành vừa thay đổi. Vui lòng tải lại.");
            if (point.confirmationKey().equals(before.key())) return choices(current, latest, admin);
            var now = LocalDateTime.now(clock);
            jdbc.update("""
                    INSERT INTO booking_pickup_snapshot
                        (booking_id,departure_id,address,landmark,arrival_time,directions_url,confirmed_at,meeting_point_id)
                    VALUES (?,?,?,?,?,?,?,?)
                    ON DUPLICATE KEY UPDATE departure_id=VALUES(departure_id),address=VALUES(address),
                        landmark=VALUES(landmark),arrival_time=VALUES(arrival_time),directions_url=VALUES(directions_url),
                        confirmed_at=VALUES(confirmed_at),meeting_point_id=VALUES(meeting_point_id)
                    """, current.id(), current.departure(), point.address(), point.landmark(),
                    time(point.arrivalTime()), point.directionsUrl(), now, point.confirmationKey());
            if (admin) audit.record(access.accountId(), "PICKUP_ASSIGNED_BY_ADMIN", "BOOKING", current.id(),
                    "Đơn #" + current.id(), "Chọn điểm đón thay khách", reason,
                    before.key() == null ? Map.of("pickupStatus", before.status()) : Map.of("meetingPointId", before.key()),
                    Map.of("meetingPointId", point.confirmationKey(), "address", point.address()));
            if (email != null) enqueue(current, latest, point, email,
                    "booking:" + current.id() + ":pickup-selection:" + digest(point.confirmationKey() + ":" + now));
            return choices(current, latest, admin);
        });
    }

    public Announcement announce(int departureId) {
        if (!access.can("TOUR_DEPARTURES_UPDATE"))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn chưa được cấp quyền thông báo điểm đón");
        var departure = departure(departureId);
        if (!departure.open() || !departure.date().isAfter(LocalDateTime.now(clock)) || departure.options().isEmpty())
            throw closed("Cần chuyến chưa khởi hành và có ít nhất một điểm đón.");
        var orders = jdbc.query("""
                SELECT id,account_id,tour_departure_id,status FROM booking
                WHERE tour_departure_id=? AND status='CONFIRMED' ORDER BY id
                """, (rs, i) -> new Booking(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getString(4)), departureId);
        var ids = orders.stream().map(order -> (long) order.account()).distinct().toList();
        var emails = new HashMap<Integer, String>();
        for (int start = 0; start < ids.size(); start += 100) {
            var group = ids.subList(start, Math.min(start + 100, ids.size()));
            for (var user : auth.getUsersBatch(group)) {
                if (user.get("id") instanceof Number id && validEmail(user.get("email")) != null)
                    emails.put(id.intValue(), validEmail(user.get("email")));
            }
        }
        String revision = digest(String.join(":", departure.options().stream().map(Option::confirmationKey).toList()));
        int queued = 0, existing = 0, missing = 0;
        for (var order : orders) {
            String email = emails.get(order.account());
            if (email == null) { missing++; continue; }
            String selected = selection(jdbc, order.id(), order.departure(), departure.options()).key();
            Option confirmed = departure.options().stream().filter(option -> option.confirmationKey().equals(selected))
                    .findFirst().orElse(null);
            if (enqueue(order, departure, confirmed, email, "booking:" + order.id() + ":pickup-published:" + revision)) queued++;
            else existing++;
        }
        return new Announcement(queued, existing, missing);
    }

    private Choices choices(Booking booking, Departure departure, boolean admin) {
        var selected = selection(jdbc, booking.id(), booking.departure(), departure.options());
        return new Choices(departure.options(), selected.key(), departure.chooseUntil(),
                editable(booking, departure, admin), selected.status());
    }

    private boolean editable(Booking booking, Departure departure, boolean admin) {
        var now = LocalDateTime.now(clock);
        if (!"CONFIRMED".equals(booking.status()) || !departure.open() || departure.options().isEmpty()) return false;
        if (!admin) return now.isBefore(departure.chooseUntil());
        var started = jdbc.queryForObject("""
                SELECT COUNT(*) FROM departure_manifest
                WHERE departure_id=? AND (actual_departure_at IS NOT NULL OR completed_at IS NOT NULL)
                """, Integer.class, booking.departure());
        return started == 0 && departure.options().stream().anyMatch(option -> canActuallyMeet(departure, option, now));
    }

    private static boolean canActuallyMeet(Departure departure, Option option, LocalDateTime now) {
        var time = time(option.arrivalTime());
        if (time == null) return false;
        var meeting = departure.date().toLocalDate().atTime(time);
        if (meeting.isAfter(departure.date())) meeting = meeting.minusDays(1);
        return now.isBefore(meeting);
    }

    private void requireAdmin() {
        if (!access.admin())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Chỉ quản trị viên được chọn điểm đón thay khách.");
    }

    private Booking ownedBooking(int bookingId) {
        int account = access.accountId();
        var booking = booking(bookingId, false);
        if (booking.account() != account)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Đơn đặt chỗ không thuộc tài khoản này");
        return booking;
    }

    private Booking booking(int id, boolean lock) {
        var rows = jdbc.query("SELECT id,account_id,tour_departure_id,status FROM booking WHERE id=?"
                + (lock ? " FOR UPDATE" : ""), (rs, i) ->
                new Booking(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getString(4)), id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy đơn đặt chỗ");
        return rows.getFirst();
    }

    private Departure departure(int id) {
        var data = tours.getDeparture(id);
        var when = BookingMailEvents.date(data.get("departure_date"));
        var options = parseOptions(data);
        LocalDateTime deadline = when.minusMinutes(90);
        for (var option : options) {
            var time = time(option.arrivalTime());
            if (time != null) {
                var meeting = when.toLocalDate().atTime(time);
                if (meeting.isAfter(when)) meeting = meeting.minusDays(1);
                if (meeting.isBefore(deadline)) deadline = meeting;
            }
        }
        return new Departure(Objects.toString(data.get("tour_title"), "Tour SGU"), when, deadline,
                "OPEN".equals(data.get("operational_status")) || "REPLACEMENT_ONLY".equals(data.get("operational_status")),
                List.copyOf(options));
    }

    static List<Option> parseOptions(Map<String, Object> data) {
        var result = new ArrayList<Option>();
        if (data.get("meeting_points") instanceof List<?> points) {
            for (var item : points) {
                if (item instanceof Map<?, ?> point && point.get("confirmationKey") instanceof String key
                        && point.get("address") instanceof String address && !address.isBlank()
                        && point.get("arrivalTime") instanceof String arrival) {
                    result.add(new Option(key, address, (String) point.get("landmark"), arrival,
                            (String) point.get("directionsUrl"), (String) point.get("revisionKey")));
                }
            }
        }
        return List.copyOf(result);
    }

    static Selection selection(JdbcTemplate jdbc, int bookingId, int departureId, List<Option> options) {
        var rows = jdbc.query("""
                SELECT meeting_point_id,address,landmark,arrival_time,directions_url FROM booking_pickup_snapshot
                WHERE booking_id=? AND departure_id=?
                """, (rs, i) -> new Option(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), null), bookingId, departureId);
        if (rows.isEmpty()) return new Selection(null, PickupStatus.MISSING);
        var saved = rows.getFirst();
        String key = options.stream().filter(option -> saved.confirmationKey() != null
                ? saved.confirmationKey().equals(option.confirmationKey())
                : option.revisionKey() == null && Objects.equals(saved.address(), option.address())
                    && Objects.equals(saved.landmark(), option.landmark())
                    && Objects.equals(time(saved.arrivalTime()), time(option.arrivalTime()))
                    && Objects.equals(saved.directionsUrl(), option.directionsUrl()))
                .map(Option::confirmationKey).findFirst().orElse(null);
        return new Selection(key, key == null ? PickupStatus.NEEDS_RECONFIRMATION : PickupStatus.CONFIRMED);
    }

    private String emailFor(int accountId) {
        try {
            var user = auth.getUserById((long) accountId);
            return user == null ? null : validEmail(user.get("email"));
        } catch (feign.FeignException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Chưa kiểm tra được email người đặt. Vui lòng thử lại.");
        }
    }

    private boolean enqueue(Booking booking, Departure departure, Option selected, String recipient, String key) {
        String link = properties.getFrontendBaseUrl().replaceAll("/+$", "")
                + "/profile?bookingId=" + booking.id() + "#don-dat-tour";
        boolean openChoice = departure.chooseUntil().isAfter(LocalDateTime.now(clock));
        String title = selected != null ? "Xác nhận điểm đón" : "Thông báo điểm đón";
        String instructions = selected != null ? "Điểm đón đã xác nhận: " + optionLabel(selected)
                : openChoice ? "Chọn điểm đón cho cả đoàn trước " + DATE_TIME.format(departure.chooseUntil())
                : "Thời gian tự chọn điểm đón đã kết thúc. Vui lòng liên hệ SGU Tour để được hỗ trợ.";
        var options = selected == null ? departure.options() : List.of(selected);
        String details = String.join("\n", options.stream().map(BookingPickupService::optionLabel).toList());
        String htmlOptions = String.join("", options.stream().map(option -> "<li>" + htmlEscape(optionLabel(option)) + "</li>").toList());
        String cta = selected == null && openChoice ? "Chọn điểm đón" : "Mở vé điện tử";
        String subject = "SGU Tour · " + title + " · Đơn #" + booking.id();
        String html = "<div lang=\"vi\" style=\"font-family:Arial;color:#163632;max-width:560px;margin:auto\">"
                + "<h2 style=\"color:#146052\">" + htmlEscape(subject) + "</h2><p>" + htmlEscape(departure.title())
                + " · " + DATE_TIME.format(departure.date()) + "</p><p>" + htmlEscape(instructions) + "</p><ul>"
                + htmlOptions + "</ul><p><a href=\"" + htmlEscape(link) + "\">" + cta + "</a></p></div>";
        var payload = Map.of("eventKey", key, "eventType", selected == null ? "PICKUP_OPTIONS_PUBLISHED" : "PICKUP_CONFIRMED",
                "sourceService", "booking-service", "bookingId", booking.id(), "accountId", booking.account(),
                "recipient", recipient, "subject", subject, "html", html, "text",
                departure.title() + "\n" + instructions + "\n" + details + "\n" + cta + ": " + link,
                "scheduledAt", LocalDateTime.now(clock).toString());
        try {
            return outbox.enqueueOnce(booking.id(), key, json.writeValueAsString(payload));
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Không tạo được thông báo điểm đón", invalid);
        }
    }

    private static String optionLabel(Option option) {
        return (option.arrivalTime() == null ? "" : option.arrivalTime().substring(0, 5) + " · ") + option.address();
    }

    private static String validEmail(Object value) {
        return value instanceof String mail && mail.length() <= 254 && mail.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+") ? mail : null;
    }

    private static LocalTime time(String value) { return value == null ? null : LocalTime.parse(value); }

    private static String digest(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static ResponseStatusException closed(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
}
