package com.example.booking_service.reservation;

import com.example.booking_service.dto.request.BookingRequest;
import com.example.booking_service.dto.response.BookingResponse;
import com.example.booking_service.notification.MailEventType;
import com.example.booking_service.paging.PageSlice;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.sql.Statement;
import java.time.*;
import java.util.*;

@Service
public class BookingLifecycleService {
    /** Grace shown on reads while the background worker is about to release a due hold. */
    private static final long EXPIRY_DISPLAY_GRACE_SECONDS = 60;
    /** One account cannot keep several checkouts open and lock up the catalogue. */
    private static final int MAX_ACTIVE_HOLDS = 5;
    private static final int MAX_HELD_SEATS = 40;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ReservationPolicy policy;
    private final ReservationExpiryService expiry;
    private final BookingMailEvents events;
    private final BookingAccess access;
    private final Clock clock;
    public BookingLifecycleService(JdbcTemplate jdbc, PlatformTransactionManager manager, ReservationPolicy policy,
            ReservationExpiryService expiry, BookingMailEvents events, BookingAccess access, Clock clock) {
        this.jdbc = jdbc; transactions = new TransactionTemplate(manager); this.policy = policy;
        this.expiry = expiry; this.events = events; this.access = access; this.clock = clock;
    }
    public BookingResponse create(BookingRequest request) {
        int account = access.accountId();
        if (request == null || !String.valueOf(account).equals(request.getUserId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Không được đặt dưới tài khoản khác");
        int departureId;
        try { departureId = Integer.parseInt(request.getTourDepartureId()); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("Lịch khởi hành không hợp lệ"); }
        if (request.getListOfCustomers() == null || request.getListOfCustomers().isEmpty())
            throw new IllegalArgumentException("Cần ít nhất một hành khách");
        String clientKey = clientKey(request.getClientKey());
        expiry.expireDeparture(departureId);
        try {
            return transactions.execute(tx -> createInTransaction(account, departureId, request, clientKey));
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            // The same request raced with itself; return the booking created by the winning call.
            if (clientKey == null) throw duplicate;
            var existing = jdbc.queryForList("SELECT id FROM booking WHERE client_key=? AND account_id=?", clientKey, account);
            // The key exists but belongs to another account: a client error, not a server failure.
            if (existing.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Mã yêu cầu đã được dùng");
            return response(((Number) existing.getFirst().get("id")).intValue());
        }
    }
    private BookingResponse createInTransaction(int account, int departureId, BookingRequest request, String clientKey) {
            LocalDateTime now = LocalDateTime.now(clock).withNano(0);
            var departures = jdbc.queryForList("SELECT * FROM tour_departure WHERE id=? FOR UPDATE", departureId);
            if (departures.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy lịch khởi hành");
            var departure = departures.getFirst();
            // Lock and validate the account BEFORE any plain SELECT: the first consistent read opens
            // the REPEATABLE READ snapshot, and the replay lookup below would otherwise open it while
            // a concurrent booking of the same account is still uncommitted — letting both pass the
            // MAX_ACTIVE_HOLDS quota check. Lock order stays departure -> account.
            var accounts = jdbc.queryForList("SELECT status FROM users WHERE id=? FOR UPDATE", account);
            if (accounts.size() != 1 || !"ACTIVE".equals(accounts.getFirst().get("status")))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tài khoản không hoạt động");
            // Idempotent replay returns the original booking even after the departure closed or sold out.
            if (clientKey != null) {
                var replay = jdbc.queryForList("SELECT id,account_id FROM booking WHERE client_key=?", clientKey);
                if (!replay.isEmpty()) {
                    if (((Number) replay.getFirst().get("account_id")).intValue() != account)
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "Mã yêu cầu đã được dùng");
                    BookingResponse existing = response(((Number) replay.getFirst().get("id")).intValue());
                    existing.setMessage("Yêu cầu đặt tour đã được xử lý trước đó");
                    return existing;
                }
            }
            if (!"OPEN".equals(departure.getOrDefault("operational_status","OPEN")))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Chuyến đang dừng hoặc dành riêng cho khách bị dời lịch; không nhận đặt mới");
            var plan = policy.plan(now, BookingMailEvents.date(departure.get("departure_date")));
            int seats = request.getListOfCustomers().size();
            if (seats > MAX_HELD_SEATS)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Mỗi booking tối đa " + MAX_HELD_SEATS + " hành khách");
            if (seats > ((Number)departure.get("available_seats")).intValue())
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Chuyến đi không còn đủ chỗ");
            var usage = jdbc.queryForMap("""
                SELECT COUNT(*) AS holds,COALESCE(SUM(r.seats),0) AS seats
                FROM booking_reservation r JOIN booking b ON b.id=r.booking_id
                WHERE b.account_id=? AND r.status IN ('HELD','CHECKOUT')
                """, account);
            if (((Number) usage.get("holds")).intValue() >= MAX_ACTIVE_HOLDS)
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Bạn đang có " + MAX_ACTIVE_HOLDS + " lượt giữ chỗ chưa thanh toán; hãy hoàn tất hoặc hủy bớt");
            if (((Number) usage.get("seats")).intValue() + seats > MAX_HELD_SEATS)
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Số chỗ đang giữ chưa thanh toán vượt mức " + MAX_HELD_SEATS + "; hãy hoàn tất hoặc hủy bớt");
            List<Passenger> passengers = new ArrayList<>();
            for (var customer : request.getListOfCustomers()) {
                if (customer == null || customer.getFullName() == null || customer.getFullName().isBlank() || customer.getFullName().length() > 255)
                    throw new IllegalArgumentException("Tên hành khách không hợp lệ");
                LocalDate birth;
                try { birth = LocalDate.parse(customer.getBirthdate()); }
                catch (RuntimeException invalid) { throw new IllegalArgumentException("Ngày sinh không hợp lệ"); }
                if (birth.isAfter(now.toLocalDate())) throw new IllegalArgumentException("Ngày sinh không được ở tương lai");
                if (!"Male".equalsIgnoreCase(customer.getGender()) && !"Female".equalsIgnoreCase(customer.getGender()))
                    throw new IllegalArgumentException("Giới tính không hợp lệ");
                String address = customer.getAddress() == null ? null : customer.getAddress().trim();
                if (address != null && address.length() > 255)
                    throw new IllegalArgumentException("Địa chỉ không được vượt quá 255 ký tự");
                int age = Period.between(birth, now.toLocalDate()).getYears();
                String type = age < 2 ? "INFANT" : age <= 4 ? "TODDLER" : age <= 11 ? "CHILD" : "ADULT";
                passengers.add(new Passenger(customer.getFullName().trim(), birth,
                        "Male".equalsIgnoreCase(customer.getGender()), address, type));
            }
            // Giá được chốt ngay khi tạo booking, không đọc lại bảng giá lúc thanh toán.
            PriceSnapshot snapshot = snapshotFor(((Number) departure.get("tour_id")).intValue(), passengers);
            var key = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                var statement = connection.prepareStatement("INSERT INTO booking (account_id,tour_departure_id,status,created_at,client_key) VALUES (?,?,'PENDING_PAYMENT',?,?)", Statement.RETURN_GENERATED_KEYS);
                statement.setInt(1, account); statement.setInt(2, departureId); statement.setObject(3, now);
                statement.setString(4, clientKey); return statement;
            }, key);
            int bookingId = Objects.requireNonNull(key.getKey()).intValue();
            for (var passenger : passengers) {
                jdbc.update("INSERT INTO customer (booking_id,full_name,birthdate,gender,address,booking_type,status) VALUES (?,?,?,?,?,?,'BOOKED')",
                        bookingId, passenger.fullName(), passenger.birthdate(), passenger.male(), passenger.address(), passenger.type());
            }
            persistSnapshot(bookingId, snapshot, now);
            jdbc.update("UPDATE tour_departure SET available_seats=available_seats-?,version=version+1 WHERE id=?", seats, departureId);
            jdbc.update("""
                INSERT INTO booking_reservation (booking_id,departure_id,departure_at,seats,status,expires_at,
                  reminder_one_at,reminder_two_at,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)
                """, bookingId, departureId, departure.get("departure_date"), seats, plan.immediate() ? "CHECKOUT" : "HELD",
                    plan.deadline(), plan.reminderOne(), plan.reminderTwo(), now, now);
            if (!plan.immediate()) events.enqueue(bookingId, MailEventType.BOOKING_CREATED, plan.deadline());
            var result = response(bookingId);
            result.setMessage(plan.immediate() ? "Vui lòng thanh toán ngay để hoàn tất đặt tour" : "Đã giữ chỗ đến hạn thanh toán");
            return result;
    }
    /** Optional idempotency key; a retried request with the same key returns the original booking. */
    private static String clientKey(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            java.util.UUID.fromString(value);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Mã yêu cầu phải là UUID");
        }
        if (!java.util.UUID.fromString(value).toString().equals(value))
            throw new IllegalArgumentException("Mã yêu cầu phải là UUID");
        return value;
    }
    public BookingResponse cancel(int id) {
        var before = booking(id);
        access.requireOwner(((Number)before.get("account_id")).intValue());
        int departureId = ((Number)before.get("tour_departure_id")).intValue();
        expiry.expireDeparture(departureId);
        return transactions.execute(tx -> {
            // A legacy booking can outlive its schedule; the lock read must answer the same 409 the
            // rest of the lifecycle uses instead of surfacing EmptyResultDataAccessException as a 500.
            if (jdbc.queryForList("SELECT id FROM tour_departure WHERE id=? FOR UPDATE", departureId).isEmpty())
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Lịch khởi hành không còn tồn tại");
            var booking = jdbc.queryForMap("SELECT * FROM booking WHERE id=? FOR UPDATE", id);
            access.requireOwner(((Number)booking.get("account_id")).intValue());
            if (((Number)booking.get("tour_departure_id")).intValue() != departureId)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Booking đã thay đổi, vui lòng tải lại");
            if (List.of("CANCELLED", "EXPIRED").contains(String.valueOf(booking.get("status")))) return response(id);
            if (expiry.paidEvidence(id)) throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Booking đã thanh toán; vui lòng liên hệ để xử lý hủy và hoàn tiền");
            if (expiry.paymentInFlight(id, LocalDateTime.now(clock).withNano(0)))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Đang có giao dịch thanh toán chờ xử lý; vui lòng đợi kết quả trước khi hủy");
            var holds = jdbc.queryForList("SELECT * FROM booking_reservation WHERE booking_id=? FOR UPDATE", id);
            if (!holds.isEmpty()) {
                var hold = holds.getFirst();
                if (((Number)hold.get("departure_id")).intValue() != departureId)
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Lịch giữ chỗ không khớp, cần kiểm tra");
                int seats=((Number)hold.get("seats")).intValue();
                if(seats<=0 || seats!=jdbc.queryForObject("SELECT COUNT(*) FROM customer WHERE booking_id=?",Integer.class,id))
                    throw new ResponseStatusException(HttpStatus.CONFLICT,"Số khách và chỗ giữ không khớp; cần kiểm tra trước khi trả chỗ");
                if (List.of("HELD","CHECKOUT").contains(hold.get("status"))) {
                    // Clamp to capacity so an already-inflated counter cannot block the customer's
                    // cancellation; the hold is always closed and seats never exceed capacity.
                    // available_seats is SMALLINT, so legacy rows without capacity cap at 32767.
                    jdbc.update("UPDATE tour_departure SET available_seats=LEAST(COALESCE(capacity,32767),available_seats+?),version=version+1 WHERE id=?", hold.get("seats"), departureId);
                    jdbc.update("UPDATE booking_reservation SET status='CANCELLED',cancel_reason='USER_CANCELLED',updated_at=? WHERE booking_id=?", LocalDateTime.now(clock), id);
                } else throw new ResponseStatusException(HttpStatus.CONFLICT, "Trạng thái giữ chỗ cần kiểm tra");
            } else jdbc.update("UPDATE tour_departure SET version=version+1 WHERE id=?",departureId);
            int cancelled = jdbc.update("UPDATE booking SET status='CANCELLED' WHERE id=? AND status IN ('PENDING_PAYMENT','CONFIRMED')", id);
            if (cancelled == 1) {
                // A pending change request must not outlive its booking and keep blocking rebooking.
                var now = LocalDateTime.now(clock);
                for (var change : jdbc.queryForList("SELECT id FROM booking_change WHERE booking_id=? AND active_key=1", id)) {
                    long changeId = ((Number) change.get("id")).longValue();
                    jdbc.update("UPDATE booking_change SET status='WITHDRAWN',active_key=NULL,version=version+1,updated_at=? WHERE id=?", now, changeId);
                    jdbc.update("""
                            INSERT INTO booking_change_event(booking_change_id,actor_id,action,note,created_at)
                            VALUES(?,?,?,?,?)
                            """, changeId, access.accountId(), "WITHDRAWN", "Booking đã hủy; yêu cầu dời lịch kết thúc", now);
                }
                events.enqueue(id, MailEventType.BOOKING_CANCELLED, null);
            }
            return response(id);
        });
    }
    public BookingResponse get(int id) {
        var booking = booking(id);
        int owner = ((Number)booking.get("account_id")).intValue();
        if (owner != access.accountId() && !access.can("BOOKINGS_GET_DETAIL"))
            access.requireReadOwner(owner, "BOOKINGS_GET_ALL");
        // Reading must not expire holds; the background worker does that. toResponse()
        // still presents an already-due hold as expired without writing anything.
        return response(id);
    }
    /**
     * {@code mine} is what the profile page sends: a staff account also holds BOOKINGS_GET_ALL, and
     * the global page would show it somebody else's newest bookings instead of its own history.
     */
    public List<BookingResponse> list(Integer page, Integer size, boolean mine) {
        int account = access.accountId();
        boolean all = !mine && access.can("BOOKINGS_GET_ALL");
        PageSlice slice = PageSlice.of(page, size);
        var rows = all
                ? jdbc.queryForList(SELECT_BOOKING + " ORDER BY b.id DESC LIMIT ? OFFSET ?",
                        slice.size(), slice.offset())
                : jdbc.queryForList(SELECT_BOOKING + " WHERE b.account_id=? ORDER BY b.id DESC LIMIT ? OFFSET ?",
                        account, slice.size(), slice.offset());
        return rows.stream().map(this::toResponse).toList();
    }
    private static final String SELECT_BOOKING = """
            SELECT b.*,r.status AS reservation_status,r.expires_at AS payment_deadline,
              r.seats AS hold_seats,r.departure_id AS hold_departure_id,
              d.operational_status AS departure_status,
              (SELECT COUNT(*) FROM customer c WHERE c.booking_id=b.id) AS passenger_count,
              r.reminder_one_at,r.reminder_two_at,
              s.adult_price AS snap_adult,s.child_price AS snap_child,s.toddler_price AS snap_toddler,
              s.infant_price AS snap_infant,s.total_amount AS snap_total,s.currency AS snap_currency,
              s.snapshot_at AS snap_at
            FROM booking b LEFT JOIN booking_reservation r ON r.booking_id=b.id
            LEFT JOIN tour_departure d ON d.id=b.tour_departure_id
            LEFT JOIN booking_price_snapshot s ON s.booking_id=b.id
            """;
    private Map<String,Object> booking(int id) {
        var rows = jdbc.queryForList("SELECT * FROM booking WHERE id=?", id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy booking");
        return rows.getFirst();
    }
    private BookingResponse response(int id) {
        return toResponse(jdbc.queryForMap(SELECT_BOOKING + " WHERE b.id=?", id));
    }
    private BookingResponse toResponse(Map<String,Object> row) {
        String storedStatus = String.valueOf(row.get("status"));
        String hold = (String) row.get("reservation_status");
        LocalDateTime deadline = row.get("payment_deadline") == null ? null : BookingMailEvents.date(row.get("payment_deadline"));
        int bookingId = ((Number) row.get("id")).intValue();
        LocalDateTime now = LocalDateTime.now(clock);
        // The background worker releases due holds a little later; present the same view
        // during that window without writing to the database on a read request. Payment
        // evidence or an in-flight checkout keeps the same guard the worker applies, so a
        // paid booking is never shown as expired before its IPN lands. The worker also skips
        // suspended departures and inconsistent holds, so the display must not contradict it.
        boolean consistentHold = row.get("hold_departure_id") != null
                && row.get("hold_departure_id").equals(row.get("tour_departure_id"))
                && row.get("hold_seats") != null && row.get("passenger_count") != null
                && ((Number) row.get("hold_seats")).longValue() == ((Number) row.get("passenger_count")).longValue();
        boolean dueHold = deadline != null && ("HELD".equals(hold) || "CHECKOUT".equals(hold))
                && consistentHold
                && !"SUSPENDED".equals(String.valueOf(row.get("departure_status")))
                && !deadline.plusSeconds(EXPIRY_DISPLAY_GRACE_SECONDS).isAfter(now)
                && !expiry.paidEvidence(bookingId)
                && !expiry.paymentInFlight(bookingId, now);
        var builder = BookingResponse.builder().id(String.valueOf(row.get("id"))).accountId(String.valueOf(row.get("account_id")))
                .tourDepartureId(String.valueOf(row.get("tour_departure_id")))
                .createdAt(BookingMailEvents.date(row.get("created_at")).toString())
                .status(dueHold ? "EXPIRED" : storedStatus).reservationStatus(dueHold ? "EXPIRED" : hold)
                .paymentDeadline(deadline == null ? null : deadline.toString())
                // Same payable-departure rule the payment service applies when an order is created:
                // a stopped or missing schedule must not invite a checkout that can only be rejected.
                .paymentRequiredNow(!dueHold && "CHECKOUT".equals(hold) && departureIsPayable(row));
        if (row.get("snap_total") != null) {
            builder.totalAmount(new BigDecimal(row.get("snap_total").toString()).toBigIntegerExact().toString())
                    .currency(String.valueOf(row.get("snap_currency")))
                    .priceSnapshotAt(BookingMailEvents.date(row.get("snap_at")).toString())
                    .unitPrices(unitPrices(row));
        }
        return builder.build();
    }

    private static boolean departureIsPayable(Map<String, Object> row) {
        Object status = row.get("departure_status");
        return status != null && !List.of("SUSPENDED", "CANCELLED").contains(String.valueOf(status));
    }

    private static Map<String, String> unitPrices(Map<String, Object> row) {
        Map<String, String> units = new LinkedHashMap<>();
        units.put("ADULT", String.valueOf(row.get("snap_adult")));
        units.put("CHILD", String.valueOf(row.get("snap_child")));
        units.put("TODDLER", String.valueOf(row.get("snap_toddler")));
        units.put("INFANT", String.valueOf(row.get("snap_infant")));
        return units;
    }

    private record Passenger(String fullName, LocalDate birthdate, boolean male, String address, String type) {}

    private record PriceSnapshot(long priceId, BigDecimal adult, BigDecimal child, BigDecimal toddler,
            BigDecimal infant, BigDecimal supplement, long total) {}

    private PriceSnapshot snapshotFor(int tourId, List<Passenger> passengers) {
        var rows = jdbc.queryForList("SELECT p.* FROM prices p JOIN tour t ON t.price_id=p.id WHERE t.id=?", tourId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Tour chưa có bảng giá để đặt");
        var price = rows.getFirst();
        BigDecimal adult = price(price, "adult_price");
        BigDecimal child = price(price, "child_price");
        BigDecimal toddler = price(price, "toddler_price");
        BigDecimal infant = price(price, "infant_price");
        BigDecimal supplement = price(price, "single_supplement_price");
        BigDecimal total = BigDecimal.ZERO;
        for (var passenger : passengers) {
            total = total.add(switch (passenger.type()) {
                case "ADULT" -> adult;
                case "CHILD" -> child;
                case "TODDLER" -> toddler;
                case "INFANT" -> infant;
                default -> throw new IllegalArgumentException("Loại hành khách không hợp lệ");
            });
        }
        long amount;
        try { amount = total.longValueExact(); }
        catch (ArithmeticException invalid) { throw new IllegalArgumentException("Tổng tiền booking không hợp lệ"); }
        if (amount <= 0 || amount > 9_999_999_999L)
            throw new IllegalArgumentException("Tổng tiền booking không hợp lệ");
        return new PriceSnapshot(((Number) price.get("id")).longValue(), adult, child, toddler, infant, supplement, amount);
    }

    private static BigDecimal price(Map<String, Object> row, String column) {
        Object value = row.get(column);
        if (value == null) return BigDecimal.ZERO;
        BigDecimal price = new BigDecimal(value.toString());
        if (price.signum() < 0) throw new IllegalArgumentException("Bảng giá không hợp lệ");
        return price;
    }

    private void persistSnapshot(int bookingId, PriceSnapshot snapshot, LocalDateTime now) {
        jdbc.update("""
                INSERT INTO booking_price_snapshot (booking_id,price_id,adult_price,child_price,toddler_price,
                  infant_price,single_supplement_price,total_amount,currency,snapshot_at)
                VALUES (?,?,?,?,?,?,?,?,'VND',?)
                """, bookingId, snapshot.priceId(), snapshot.adult(), snapshot.child(), snapshot.toddler(),
                snapshot.infant(), snapshot.supplement(), snapshot.total(), now);
    }
}
