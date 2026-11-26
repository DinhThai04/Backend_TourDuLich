package com.example.booking_service.reservation;

import com.example.booking_service.dto.request.BookingRequest;
import com.example.booking_service.dto.response.BookingResponse;
import com.example.booking_service.notification.MailEventType;
import com.example.booking_service.paging.PageSlice;
import com.example.booking_service.repository.httpclient.AuthInternalClient;
import com.example.booking_service.repository.httpclient.InvoiceInternalClient;
import com.example.booking_service.repository.httpclient.PricingInternalClient;
import com.example.booking_service.repository.httpclient.TourInternalClient;
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
    private final AuthInternalClient authClient;
    private final InvoiceInternalClient invoiceClient;
    private final PricingInternalClient pricingClient;
    private final TourInternalClient tourClient;

    public BookingLifecycleService(JdbcTemplate jdbc, PlatformTransactionManager manager, ReservationPolicy policy,
            ReservationExpiryService expiry, BookingMailEvents events, BookingAccess access, Clock clock,
            AuthInternalClient authClient, InvoiceInternalClient invoiceClient,
            PricingInternalClient pricingClient, TourInternalClient tourClient) {
        this.jdbc = jdbc; transactions = new TransactionTemplate(manager); this.policy = policy;
        this.expiry = expiry; this.events = events; this.access = access; this.clock = clock;
        this.authClient = authClient; this.invoiceClient = invoiceClient;
        this.pricingClient = pricingClient; this.tourClient = tourClient;
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
        int seats = request.getListOfCustomers().size();
        if (seats > MAX_HELD_SEATS)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Mỗi booking tối đa " + MAX_HELD_SEATS + " hành khách");
        expiry.expireDeparture(departureId);
        try {
            return transactions.execute(tx -> createInTransaction(account, departureId, request, clientKey));
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            if (clientKey == null) throw duplicate;
            // The winning write can be replayed only when it represents the same request.
            return transactions.execute(tx -> {
                var existing = replay(account, departureId, clientKey, passengers(request, LocalDate.now(clock)));
                if (existing == null) throw duplicate;
                return existing;
            });
        }
    }
    private BookingResponse createInTransaction(int account, int departureId, BookingRequest request, String clientKey) {
            // Get departure info via Tour internal API (replaces direct tour_departure query)
            Map<String, Object> departure;
            try {
                departure = tourClient.getDeparture(departureId);
            } catch (Exception e) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy lịch khởi hành");
            }
if (departure == null || departure.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy lịch khởi hành");
            }
            // Concurrency for seat consumption is owned by Tour: holdSeats() below locks the
            // departure row on the Tour side and decrements available_seats atomically, so
            // Booking must not SELECT ... FOR UPDATE tour_departure itself.
            // Lock and validate the account via Auth internal API (replaces direct users query)
            Map<String, Object> userStatus;
            try {
                userStatus = authClient.getUserStatus((long) account);
            } catch (Exception e) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Chưa xác minh được tài khoản, vui lòng thử lại");
            }
            if (userStatus == null || userStatus.isEmpty() || !"ACTIVE".equals(userStatus.get("status"))) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tài khoản không hoạt động");
            }
            LocalDateTime now = LocalDateTime.now(clock).withNano(0);
            var passengers = passengers(request, now.toLocalDate());
            if (clientKey != null) {
                var existing = replay(account, departureId, clientKey, passengers);
                if (existing != null) return existing;
            }
            if (request.getPricingDate() != null && !now.toLocalDate().equals(request.getPricingDate()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Đã sang ngày mới theo giờ Việt Nam. Vui lòng kiểm tra lại giá trước khi đặt.");
            if (!"OPEN".equals(departure.getOrDefault("operational_status","OPEN")))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Chuyến đang dừng hoặc dành riêng cho khách bị dời lịch; không nhận đặt mới");
            var departureAt = BookingMailEvents.date(departure.get("departure_date"));
            policy.requireBookable(now, departureAt);
            Map<?, ?> pickup = confirmedPickup(departure, request.getPickupConfirmationKey());
            var plan = policy.plan(now, departureAt);
            int seats = passengers.size();
            Integer availableSeats = (Integer) departure.get("available_seats");
            if (availableSeats == null || seats > availableSeats)
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
            // Giá được chốt ngay khi tạo booking, không đọc lại bảng giá lúc thanh toán.
            PriceSnapshot snapshot = snapshotFor(tourIdOf(departure.get("tour_id")), passengers);
            String expected = request.getExpectedAmount();
            if (expected != null && BigDecimal.valueOf(snapshot.total()).compareTo(new BigDecimal(expected)) != 0)
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Giá chuyến đi đã thay đổi. Vui lòng xem lại tổng tiền trước khi giữ chỗ.");
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
            if (pickup != null) {
                jdbc.update("""
                        INSERT INTO booking_pickup_snapshot
                            (booking_id,departure_id,address,landmark,arrival_time,directions_url,confirmed_at)
                        VALUES (?,?,?,?,?,?,?)
                        """, bookingId, departureId, pickup.get("address"), pickup.get("landmark"),
                        pickup.get("arrivalTime"), pickup.get("directionsUrl"), now);
            }
            // Seat consumption is owned by Tour: holdSeats locks the departure row on the Tour side
            // and decrements available_seats atomically, so Booking must not write tour_departure.
            LocalDateTime expiresAt = policy.capDeadline(plan.deadline(), departureAt);
            try {
                tourClient.holdSeats(departureId, new TourInternalClient.HoldSeatsRequest(
                        seats, String.valueOf(bookingId), expiresAt, Objects.toString(request.getPickupConfirmationKey(), "")));
            } catch (feign.FeignException.Conflict changed) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Không thể giữ chỗ theo thông tin đã hiển thị. Vui lòng kiểm tra lại giờ đóng đặt và điểm tập trung.");
            } catch (Exception e) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Không thể giữ chỗ tại tour-service");
            }
            try {
                jdbc.update("""
                    INSERT INTO booking_reservation (booking_id,departure_id,departure_at,seats,status,expires_at,
                      reminder_one_at,reminder_two_at,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)
                    """, bookingId, departureId, BookingMailEvents.date(departure.get("departure_date")), seats, plan.immediate() ? "CHECKOUT" : "HELD",
                        expiresAt, plan.reminderOne(), plan.reminderTwo(), now, now);
                events.enqueue(bookingId, MailEventType.BOOKING_CREATED, expiresAt);
                var result = response(bookingId);
                result.setMessage(plan.immediate() ? "Vui lòng thanh toán ngay để hoàn tất đặt tour" : "Đã giữ chỗ đến hạn thanh toán");
                return result;
            } catch (RuntimeException ex) {
                try {
                    tourClient.releaseHold(departureId, seats, bookingId);
                } catch (Exception releaseEx) {
                    // Best-effort seat compensation when local DB transaction fails after remote hold.
                }
                throw ex;
            }
    }
    private Map<?, ?> confirmedPickup(Map<String, Object> departure, String selection) {
        List<?> options;
        if (departure.get("meeting_points") instanceof List<?> stops) options = stops;
        else if (departure.get("meeting_point") instanceof Map<?, ?> stop) options = List.of(stop);
        else options = List.of();
        if (options.isEmpty() && selection == null) return null;
        for (Object value : options) {
            if (value instanceof Map<?, ?> stop && selection != null && selection.equals(stop.get("confirmationKey"))) return stop;
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Vui lòng tải lại trang và xác nhận thông tin điểm tập trung hiện tại.");
    }

    private BookingResponse replay(int account, int departureId, String clientKey, List<Passenger> passengers) {
        var rows = jdbc.queryForList("""
                SELECT b.id,b.account_id,b.tour_departure_id,b.status,r.status AS persisted_hold_status
                FROM booking b LEFT JOIN booking_reservation r ON r.booking_id=b.id WHERE b.client_key=?
                """, clientKey);
        if (rows.isEmpty()) return null;
        var row = rows.getFirst();
        if (((Number) row.get("account_id")).intValue() != account)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Mã yêu cầu đã được dùng");

        int id = ((Number) row.get("id")).intValue();
        if (((Number) row.get("tour_departure_id")).intValue() != departureId)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Lượt giữ chỗ trước thuộc lịch khác. Vui lòng kiểm tra đơn trước khi đặt lại.");
        var storedHold = row.get("persisted_hold_status");
        if (List.of("CANCELLED", "EXPIRED").contains(row.get("status"))
                && (storedHold == null || List.of("CANCELLED", "EXPIRED").contains(storedHold))
                && !expiry.paidEvidence(id) && !expiry.paymentInFlight(id, LocalDateTime.now(clock))) {
            var closed = response(id);
            closed.setRestartAllowed(true);
            closed.setMessage("Đơn trước đã kết thúc. Bạn có thể tạo lượt giữ chỗ mới.");
            return closed;
        }
        var saved = jdbc.query("""
                SELECT full_name,birthdate,gender,address FROM customer WHERE booking_id=? ORDER BY id
                """, (rs, index) -> new PassengerIdentity(rs.getString("full_name"),
                rs.getObject("birthdate", LocalDate.class), rs.getBoolean("gender"), rs.getString("address")), id);
        var submitted = passengers.stream()
                .map(p -> new PassengerIdentity(p.fullName(), p.birthdate(), p.male(), p.address())).toList();
        if (!saved.equals(submitted))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Lượt giữ chỗ trước đã được xử lý với thông tin khác. Vui lòng kiểm tra đơn trước khi đặt lại.");
        var existing = response(id);
        existing.setMessage("Yêu cầu đặt tour đã được xử lý trước đó");
        return existing;
    }

    private static List<Passenger> passengers(BookingRequest request, LocalDate businessDate) {
        List<Passenger> passengers = new ArrayList<>();
        for (var customer : request.getListOfCustomers()) {
            if (customer == null || customer.getFullName() == null || customer.getFullName().isBlank() || customer.getFullName().trim().length() < 2
                    || customer.getFullName().trim().length() > 100
                    || !customer.getFullName().trim().matches("[\\p{L}\\p{M}]+(?: +[\\p{L}\\p{M}]+)*"))
                throw new IllegalArgumentException("Tên hành khách không hợp lệ");
            LocalDate birth;
            try { birth = LocalDate.parse(customer.getBirthdate()); }
            catch (RuntimeException invalid) { throw new IllegalArgumentException("Ngày sinh không hợp lệ"); }
            if (birth.isAfter(businessDate)) throw new IllegalArgumentException("Ngày sinh không được ở tương lai");
            if (!"Male".equalsIgnoreCase(customer.getGender()) && !"Female".equalsIgnoreCase(customer.getGender()))
                throw new IllegalArgumentException("Giới tính không hợp lệ");
            String address = customer.getAddress() == null ? null : customer.getAddress().trim();
            if (address != null && (address.length() > 255 || !address.matches("[\\p{L}\\p{M}\\p{N} .,/()–-]*")))
                throw new IllegalArgumentException("Địa chỉ không hợp lệ hoặc vượt quá 255 ký tự");
            int age = Period.between(birth, businessDate).getYears();
            String type = age < 2 ? "INFANT" : age <= 4 ? "TODDLER" : age <= 11 ? "CHILD" : "ADULT";
            passengers.add(new Passenger(customer.getFullName().trim(), birth,
                    "Male".equalsIgnoreCase(customer.getGender()), address, type));
        }
        return passengers;
    }

    private record PassengerIdentity(String fullName, LocalDate birthdate, boolean male, String address) {}

    /** Optional idempotency key. Only a replay of the same booking request can reuse it. */
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
            // Check departure exists via Tour internal API
            Map<String, Object> departure;
            try {
                departure = tourClient.getDeparture(departureId);
            } catch (Exception e) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Lịch khởi hành không còn tồn tại");
            }
            if (departure == null || departure.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Lịch khởi hành không còn tồn tại");
            }
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
                    // Release seats via Tour internal API
                    try {
                        tourClient.releaseHold(departureId, seats, id);
                    } catch (Exception e) {
                        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Không thể giải phóng chỗ tại tour-service");
                    }
                    jdbc.update("UPDATE booking_reservation SET status='CANCELLED',cancel_reason='USER_CANCELLED',updated_at=? WHERE booking_id=?", LocalDateTime.now(clock), id);
                } else throw new ResponseStatusException(HttpStatus.CONFLICT, "Trạng thái giữ chỗ cần kiểm tra");
            } else {
                try {
                    tourClient.incrementVersion(departureId);
                } catch (Exception e) {
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Không thể cập nhật phiên bản lịch khởi hành tại tour-service");
                }
            }
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
        var result = response(id);
        var pickup = jdbc.query("""
                SELECT address,landmark,arrival_time,directions_url FROM booking_pickup_snapshot
                WHERE booking_id=? AND departure_id=?
                """, (rs, row) -> new BookingResponse.Pickup(rs.getString("address"), rs.getString("landmark"),
                rs.getString("arrival_time"), rs.getString("directions_url")), id, Integer.parseInt(result.getTourDepartureId()));
        pickup.stream().findFirst().ifPresent(result::setPickup);
        return result;
    }

    public BookingResponse findOwnAttempt(String key) {
        int account = access.accountId();
        String requestKey = clientKey(key);
        if (requestKey == null) throw new IllegalArgumentException("Thiếu mã lượt giữ chỗ");
        var rows = jdbc.queryForList(SELECT_BOOKING + " WHERE b.account_id=? AND b.client_key=?", account, requestKey);
        if (rows.isEmpty()) return null;
        var row = rows.getFirst();
        var result = toResponse(row);
        int id = ((Number) row.get("id")).intValue();
        Object hold = row.get("reservation_status");
        boolean closed = List.of("EXPIRED", "CANCELLED").contains(row.get("status"))
                && (hold == null || List.of("EXPIRED", "CANCELLED").contains(hold));
        result.setRestartAllowed(closed && !expiry.paidEvidence(id) && !expiry.paymentInFlight(id, LocalDateTime.now(clock)));
        return result;
    }

    public record HistoryRow(BookingResponse booking, String tourCode, String tourTitle,
                             String departureDate, String returnDate, String invoiceStatus, Long totalAmount) {}
    public record HistoryPage(List<HistoryRow> items, boolean hasNext) {}

    public HistoryPage ownHistory(int page, Long bookingId, String status, LocalDate from, LocalDate to) {
        int account = access.accountId();
        if (page < 0 || page > 10000 || (bookingId != null && bookingId < 1)
                || (from != null && to != null && from.isAfter(to)))
            throw new IllegalArgumentException("Bộ lọc lịch sử đặt tour không hợp lệ");
        // Base query without cross-DB JOINs
        String sql = SELECT_BOOKING;
        var clauses = new StringBuilder(" WHERE b.account_id=?");
        var args = new java.util.ArrayList<Object>();
        args.add(account);
        if (bookingId != null) { clauses.append(" AND b.id=?"); args.add(bookingId); }
        if (from != null) { clauses.append(" AND b.created_at>=?"); args.add(from.atStartOfDay()); }
        if (to != null) { clauses.append(" AND b.created_at<?"); args.add(to.plusDays(1).atStartOfDay()); }
        if (status != null && !status.isBlank()) {
            clauses.append(switch (status) {
                case "PAID" -> " AND r.status='PAID'";
                case "UNPAID" -> " AND b.status IN ('PENDING_PAYMENT','CONFIRMED') AND COALESCE(r.status,'')<>'PAID'";
                case "CLOSED" -> " AND (b.status IN ('CANCELLED','EXPIRED','UNCONFIRMED') OR r.status IN ('CANCELLED','EXPIRED'))";
                default -> throw new IllegalArgumentException("Trạng thái lịch sử không hợp lệ");
            });
        }
        int limit = bookingId == null ? 8 : 1;
        args.add(limit + 1);
        args.add(bookingId == null ? page * limit : 0);
        var rows = jdbc.queryForList(sql + clauses + " ORDER BY b.id DESC LIMIT ? OFFSET ?", args.toArray());
        
        // Enrich with tour, price snapshot, and invoice data
        List<HistoryRow> items = new ArrayList<>();
        for (var row : rows.stream().limit(limit).toList()) {
            BookingResponse bookingResp = toResponse(row);
            String tourCode = null, tourTitle = null, departureDate = null, returnDate = null, invoiceStatus = null;
            Long totalAmount = null;

            Object depObj = row.get("hold_departure_id") != null ? row.get("hold_departure_id") : row.get("tour_departure_id");
            Integer departureId = depObj instanceof Number n ? n.intValue() : null;
            if (departureId != null) {
                try {
                    Map<String, Object> departure = tourClient.getDeparture(departureId);
                    if (departure != null) {
                        tourCode = departure.get("tour_code") != null ? String.valueOf(departure.get("tour_code")) : null;
                        Object titleObj = departure.get("tour_title");
                        if (titleObj != null && !String.valueOf(titleObj).isBlank()) {
                            tourTitle = String.valueOf(titleObj);
                        } else if (departure.get("tour_id") != null) {
                            tourTitle = "Tour #" + departure.get("tour_id");
                        }
                        Object depDate = departure.get("departure_date");
                        Object retDate = departure.get("return_date");
                        departureDate = depDate != null ? BookingMailEvents.date(depDate).toString() : null;
                        returnDate = retDate != null ? BookingMailEvents.date(retDate).toString() : null;
                    }
                } catch (Exception ignored) {
                    // Tour service unavailable, continue without tour info
                }
            }

            Integer bookingIdInt = ((Number) row.get("id")).intValue();
            try {
                var snap = jdbc.queryForList("SELECT total_amount FROM booking_price_snapshot WHERE booking_id=?", bookingIdInt);
                if (!snap.isEmpty() && snap.get(0).get("total_amount") instanceof Number amt) {
                    totalAmount = amt.longValue();
                }
            } catch (Exception ignored) {}

            try {
                Map<String, Object> invoice = invoiceClient.getInvoiceByBookingId(bookingIdInt);
                if (invoice != null) {
                    invoiceStatus = invoice.get("status") != null ? String.valueOf(invoice.get("status")) : null;
                    if (totalAmount == null && invoice.get("totalBookingTourExpense") instanceof Number invAmt) {
                        totalAmount = invAmt.longValue();
                    }
                }
            } catch (Exception ignored) {
                // Invoice service unavailable, continue without invoice info
            }
            
            items.add(new HistoryRow(bookingResp, tourCode, tourTitle, departureDate, returnDate, invoiceStatus, totalAmount));
        }
        return new HistoryPage(items, rows.size() > limit);
    }

    private static String historyDate(Object value) {
        return value == null ? null : BookingMailEvents.date(value).toString();
    }
    /**
     * {@code mine} is what the profile page sends: a staff account also holds BOOKINGS_GET_ALL, and
     * the global page would show it somebody else's newest bookings instead of its own history.
     */
    public List<BookingResponse> list(Integer page, Integer size, boolean mine) {
        return list(page, size, mine, null);
    }
    public List<BookingResponse> list(Integer page, Integer size, boolean mine, Integer departureId) {
        int account = access.accountId();
        boolean all = !mine && access.can("BOOKINGS_GET_ALL");
        PageSlice slice = PageSlice.of(page, size);
        if (departureId != null) {
            var scoped = all
                    ? jdbc.queryForList(SELECT_BOOKING + " WHERE b.tour_departure_id=? ORDER BY b.id DESC LIMIT ? OFFSET ?",
                            departureId, slice.size(), slice.offset())
                    : jdbc.queryForList(SELECT_BOOKING + " WHERE b.tour_departure_id=? AND b.account_id=? ORDER BY b.id DESC LIMIT ? OFFSET ?",
                            departureId, account, slice.size(), slice.offset());
            return scoped.stream().map(this::toResponse).toList();
        }
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
              (SELECT COUNT(*) FROM customer c WHERE c.booking_id=b.id) AS passenger_count,
              CASE WHEN b.status='CONFIRMED' AND EXISTS (
                SELECT 1 FROM departure_manifest m JOIN departure_attendance a ON a.departure_id=m.departure_id
                JOIN customer c ON c.id=a.customer_id
                WHERE m.departure_id=b.tour_departure_id AND m.completed_at IS NOT NULL
                  AND a.status='RETURNED' AND c.booking_id=b.id
              ) THEN 1 ELSE 0 END AS trip_completed,
              r.reminder_one_at,r.reminder_two_at,
              s.adult_price AS snap_adult,s.child_price AS snap_child,s.toddler_price AS snap_toddler,
              s.infant_price AS snap_infant,s.total_amount AS snap_total,s.currency AS snap_currency,
              s.snapshot_at AS snap_at
            FROM booking b LEFT JOIN booking_reservation r ON r.booking_id=b.id
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
        
        // Fetch departure status via Tour internal API (replaces direct tour_departure JOIN)
        String departureStatus = "OPEN";
        Integer departureId = (Integer) row.get("hold_departure_id");
        if (departureId != null) {
            try {
                Map<String, Object> departure = tourClient.getDeparture(departureId);
                if (departure != null) {
                    Object status = departure.get("operational_status");
                    if (status != null) departureStatus = status.toString();
                }
            } catch (Exception ignored) {
                // Tour service unavailable, assume OPEN
            }
        }
        
        // The background worker releases due holds a little later; present the same view
        // during that window without writing to the database on a read request. Payment
        // evidence or an in-flight checkout keeps the same guard the worker applies, so a
        // paid booking is never shown as expired before its IPN lands. Active shop offers
        // on suspended departures and inconsistent holds follow the worker's rules too.
        boolean consistentHold = row.get("hold_departure_id") != null
                && row.get("hold_departure_id").equals(row.get("tour_departure_id"))
                && row.get("hold_seats") != null && row.get("passenger_count") != null
                && ((Number) row.get("hold_seats")).longValue() == ((Number) row.get("passenger_count")).longValue();
        boolean dueHold = deadline != null && ("HELD".equals(hold) || "CHECKOUT".equals(hold))
                && consistentHold
                && !deadline.plusSeconds(EXPIRY_DISPLAY_GRACE_SECONDS).isAfter(now)
                && (!"SUSPENDED".equals(departureStatus)
                        || !expiry.hasActiveShopOffer(bookingId, ((Number) row.get("tour_departure_id")).intValue()))
                && !expiry.paidEvidence(bookingId)
                && !expiry.paymentInFlight(bookingId, now);
        var builder = BookingResponse.builder().id(String.valueOf(row.get("id"))).accountId(String.valueOf(row.get("account_id")))
                .tourDepartureId(String.valueOf(row.get("tour_departure_id")))
                .createdAt(BookingMailEvents.date(row.get("created_at")).toString())
                .status(dueHold ? "EXPIRED" : storedStatus).reservationStatus(dueHold ? "EXPIRED" : hold)
                .tripCompleted(row.get("trip_completed") instanceof Number completed && completed.intValue() == 1)
                .paymentDeadline(deadline == null ? null : deadline.toString())
                // Same payable-departure rule the payment service applies when an order is created:
                // a stopped or missing schedule must not invite a checkout that can only be rejected.
                .paymentRequiredNow(!dueHold && "CHECKOUT".equals(hold) && departureIsPayable(departureStatus));
        if (row.get("snap_total") != null) {
            builder.totalAmount(new BigDecimal(row.get("snap_total").toString()).toBigIntegerExact().toString())
                    .currency(String.valueOf(row.get("snap_currency")))
                    .priceSnapshotAt(BookingMailEvents.date(row.get("snap_at")).toString())
                    .unitPrices(unitPrices(row));
        }
        return builder.build();
    }

    private static boolean departureIsPayable(String departureStatus) {
        return departureStatus != null && !List.of("SUSPENDED", "CANCELLED").contains(departureStatus);
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

    private int tourIdOf(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value instanceof String text) {
            try { return Integer.parseInt(text.trim()); }
            catch (RuntimeException invalid) { throw new IllegalArgumentException("Tour ID không hợp lệ"); }
        }
        throw new IllegalArgumentException("Tour ID không hợp lệ");
    }

    private PriceSnapshot snapshotFor(int tourId, List<Passenger> passengers) {
        // Get price info via Pricing internal API (replaces direct prices+tour JOIN)
        Map<String, Object> price;
        try {
            price = pricingClient.getPriceById((long) tourId);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Tour chưa có bảng giá để đặt");
        }
        if (price == null || price.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Tour chưa có bảng giá để đặt");
        }
        BigDecimal adult = price(price, "adultPrice");
        BigDecimal child = price(price, "childPrice");
        BigDecimal toddler = price(price, "toddlerPrice");
        BigDecimal infant = price(price, "infantPrice");
        BigDecimal supplement = price(price, "singleSupplementPrice");
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
