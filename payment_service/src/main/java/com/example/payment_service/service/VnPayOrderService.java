package com.example.payment_service.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** All participating services currently share dbtour_v2; settle VNPay atomically in that database. */
@Service
@RequiredArgsConstructor
@Slf4j
public class VnPayOrderService {
    private final JdbcTemplate jdbc;
    private final com.example.payment_service.notification.PaymentMailOutboxWriter mail;
    private final Clock clock;
    public record Order(String txnRef, long amountVnd, LocalDateTime createdAt, LocalDateTime expiresAt) {}

    public record Quote(long amountVnd, Map<String, String> unitPrices) {}

    /** Steps shared by every provider before a transaction row exists: lock, validate, price. */
    public record Checkout(int bookingId, int departureId, long amountVnd, LocalDateTime now, LocalDateTime expires) {}

    /** Locked booking + hold, serialized in the fixed departure -> booking -> reservation order. */
    public record LockedSettlement(Map<String, Object> booking, Map<String, Object> hold) {}

    /**
     * Tính giá phải trả mà không tạo giao dịch, để màn hình thanh toán hiển thị
     * đúng số tiền server sẽ thu và dùng lại làm expectedAmount.
     *
     * @param provider nhà cung cấp sẽ dùng báo giá này ("momo" hoặc "vnpay"); báo giá phải thất bại
     *                 đúng những lý do mà checkout của nhà cung cấp đó sẽ từ chối.
     */
    public Quote quote(int bookingId, int accountId, String provider) {
        var rows = jdbc.queryForList(
                "SELECT account_id,status,tour_departure_id FROM booking WHERE id=?", bookingId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy booking");
        var booking = rows.getFirst();
        if (((Number) booking.get("account_id")).intValue() != accountId)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Booking không thuộc tài khoản đăng nhập");
        if (!payable(booking.get("status"))) throw new IllegalArgumentException("Booking đã bị hủy hoặc hết hạn");
        if (paid(bookingId)) throw new IllegalArgumentException("Booking đã thanh toán");

        var departure = departureOf(booking.get("tour_departure_id"));
        if (List.of("SUSPENDED", "CANCELLED").contains(String.valueOf(departure.get("operational_status"))))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Chuyến đang dừng do sự cố; vui lòng xem đề xuất dời lịch");
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        if (!databaseDateTime(departure.get("departure_date")).isAfter(now))
            throw new IllegalArgumentException("Chuyến đi đã khởi hành");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM payment_reconciliation WHERE booking_id=? AND status='OPEN'", Integer.class, bookingId) > 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Booking còn giao dịch cần đối soát; vui lòng liên hệ hỗ trợ");
        // The same provider may be re-quoted while its own order is pending, but a live order of the
        // other provider makes beginCheckout reject this booking, so the quote must fail the same way.
        String otherProviderTable = "momo".equals(provider) ? "vnpay_transaction" : "momo_transaction";
        if (jdbc.queryForObject("SELECT COUNT(*) FROM " + otherProviderTable
                + " WHERE booking_id=? AND status='PENDING' AND expires_at>?", Integer.class, bookingId, now) > 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Booking đang có giao dịch thanh toán khác chờ xử lý; hãy hoàn tất hoặc chờ hết hạn");

        var customers = jdbc.queryForList("SELECT booking_type FROM customer WHERE booking_id=?", bookingId);
        if (customers.isEmpty()) throw new IllegalArgumentException("Booking chưa có hành khách");
        // The quote must fail for the same reasons checkout would, otherwise the client shows a
        // payable invoice that the pay button immediately rejects.
        var hold = reservation(bookingId);
        if (hold != null) {
            validateHold(hold, booking, customers.size(), now);
            if (!databaseDateTime(hold.get("departure_at")).equals(databaseDateTime(departure.get("departure_date"))))
                throw new IllegalArgumentException("Lịch khởi hành đã thay đổi; vui lòng liên hệ để cập nhật booking");
        } else if (((Number) departure.get("available_seats")).intValue() < customers.size()) {
            throw new IllegalArgumentException("Chuyến đi không còn đủ chỗ");
        }
        var snapshot = snapshot(bookingId);
        if (snapshot != null) return new Quote(snapshotAmount(snapshot), unitPrices(snapshot));
        var price = tourPrice(departure.get("tour_id"));
        return new Quote(calculateAmount(customers, price), unitPrices(price));
    }

    private Map<String, Object> departureOf(Object departureId) {
        var rows = jdbc.queryForList(
                "SELECT tour_id,departure_date,operational_status,available_seats FROM tour_departure WHERE id=?", departureId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Lịch khởi hành không còn tồn tại");
        return rows.getFirst();
    }

    /** A tour without a price row is a data problem for the operator, not a server fault. */
    private Map<String, Object> tourPrice(Object tourId) {
        var rows = jdbc.queryForList(
                "SELECT p.* FROM prices p JOIN tour t ON t.price_id=p.id WHERE t.id=?", tourId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Tour chưa có bảng giá để thanh toán");
        return rows.getFirst();
    }

    private static boolean payable(Object status) {
        return List.of("PENDING_PAYMENT", "CONFIRMED").contains(String.valueOf(status));
    }

    /** Snapshot is written when the booking is created; legacy bookings fall back to the live price. */
    private Map<String, Object> snapshot(int bookingId) {
        var rows = jdbc.queryForList("SELECT * FROM booking_price_snapshot WHERE booking_id=?", bookingId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static long snapshotAmount(Map<String, Object> snapshot) {
        long amount;
        try { amount = new BigDecimal(snapshot.get("total_amount").toString()).longValueExact(); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("Số tiền snapshot không hợp lệ"); }
        if (amount <= 0 || amount > 9_999_999_999L) throw new IllegalArgumentException("Số tiền không hợp lệ");
        return amount;
    }

    private static Map<String, String> unitPrices(Map<String, Object> price) {
        Map<String, String> units = new LinkedHashMap<>();
        units.put("ADULT", price.get("adult_price").toString());
        units.put("CHILD", price.get("child_price").toString());
        units.put("TODDLER", price.get("toddler_price").toString());
        units.put("INFANT", price.get("infant_price").toString());
        return units;
    }

    private Map<String, Object> booking(int id) {
        var lookup = jdbc.queryForList("SELECT tour_departure_id FROM booking WHERE id=?", id);
        if (lookup.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy booking");
        Object departureId = lookup.getFirst().get("tour_departure_id");
        // Locking the departure is the checkout lock order; a legacy row pointing at a deleted
        // schedule answers the same 409 departureOf() uses instead of an EmptyResultDataAccessException 500.
        if (jdbc.queryForList("SELECT id FROM tour_departure WHERE id=? FOR UPDATE", departureId).isEmpty())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Lịch khởi hành không còn tồn tại");
        var rows = jdbc.queryForList("SELECT * FROM booking WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy booking");
        if (!departureId.equals(rows.getFirst().get("tour_departure_id")))
            throw new IllegalArgumentException("Booking đã thay đổi, vui lòng tải lại");
        return rows.getFirst();
    }

    public static long calculateAmount(List<Map<String, Object>> customers, Map<String, Object> price) {
        if (customers.isEmpty()) throw new IllegalArgumentException("Booking chưa có hành khách");
        // No persisted single-room selection: charge passenger fares only.
        BigDecimal total = BigDecimal.ZERO;
        for (var c : customers) {
            String column = switch (c.get("booking_type").toString()) {
                case "ADULT" -> "adult_price";
                case "CHILD" -> "child_price";
                case "TODDLER" -> "toddler_price";
                case "INFANT" -> "infant_price";
                default -> throw new IllegalArgumentException("Loại hành khách không hợp lệ");
            };
            BigDecimal unit = new BigDecimal(price.get(column).toString());
            if (unit.signum() < 0) throw new IllegalArgumentException("Giá vé không hợp lệ");
            total = total.add(unit);
        }
        // Both providers price in whole VND; longValueExact would raise ArithmeticException,
        // which the controllers can only report as a 500.
        if (total.stripTrailingZeros().scale() > 0) throw new IllegalArgumentException("Bảng giá phải là số nguyên VND");
        long amount = total.longValueExact();
        // VNPay vnp_Amount is Numeric[1,12], in hundredths of VND.
        if (amount <= 0 || amount > 9_999_999_999L) throw new IllegalArgumentException("Số tiền không hợp lệ");
        return amount;
    }

    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED,timeout=10)
    public Order prepare(int bookingId, int accountId, String expectedAmount) {
        var c = beginCheckout(bookingId, accountId, expectedAmount, "vnpay");
        var pending = jdbc.queryForList("SELECT * FROM vnpay_transaction WHERE booking_id=? AND status='PENDING' AND expires_at>? ORDER BY created_at DESC LIMIT 1", bookingId, c.now());
        if (!pending.isEmpty()) {
            var p = pending.getFirst();
            if (((Number)p.get("amount_vnd")).longValue() != c.amountVnd())
                throw new IllegalArgumentException("Bảng giá đã thay đổi; hãy chờ yêu cầu thanh toán cũ hết hạn");
            return new Order(p.get("txn_ref").toString(), c.amountVnd(),
                    databaseDateTime(p.get("created_at")),
                    databaseDateTime(p.get("expires_at")));
        }
        String ref = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO vnpay_transaction (txn_ref,booking_id,account_id,amount_vnd,status,created_at,expires_at) VALUES (?,?,?,?,'PENDING',?,?)", ref, bookingId, accountId, c.amountVnd(), c.now(), c.expires());
        jdbc.update("UPDATE tour_departure SET version=version+1 WHERE id=?", c.departureId());
        return new Order(ref, c.amountVnd(), c.now(), c.expires());
    }

    /**
     * Shared checkout preamble: lock departure + booking, validate ownership/status/payment, and
     * agree the payable amount + checkout window. Each provider then stores its own transaction row.
     */
    public Checkout beginCheckout(int bookingId, int accountId, String expectedAmount, String provider) {
        var b = booking(bookingId);
        if (((Number)b.get("account_id")).intValue() != accountId)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Booking không thuộc tài khoản đăng nhập");
        if (!payable(b.get("status"))) throw new IllegalArgumentException("Booking đã bị hủy hoặc hết hạn");
        if (paid(bookingId)) throw new IllegalArgumentException("Booking đã thanh toán");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM payment_reconciliation WHERE booking_id=? AND status='OPEN'",Integer.class,bookingId)>0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Booking còn giao dịch cần đối soát; vui lòng liên hệ hỗ trợ");
        var departure = jdbc.queryForMap("SELECT * FROM tour_departure WHERE id=? FOR UPDATE", b.get("tour_departure_id"));
        if (java.util.List.of("SUSPENDED","CANCELLED").contains(String.valueOf(departure.get("operational_status"))))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Chuyến đang dừng do sự cố; vui lòng xem đề xuất dời lịch");
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        if (!databaseDateTime(departure.get("departure_date")).isAfter(now))
            throw new IllegalArgumentException("Chuyến đi đã khởi hành");
        var customers = jdbc.queryForList("SELECT booking_type FROM customer WHERE booking_id=?", bookingId);
        var hold = reservation(bookingId);
        if (hold != null) {
            validateHold(hold, b, customers.size(), now);
            if (!databaseDateTime(hold.get("departure_at")).equals(databaseDateTime(departure.get("departure_date"))))
                throw new IllegalArgumentException("Lịch khởi hành đã thay đổi; vui lòng liên hệ để cập nhật booking");
        }
        if (hold == null && ((Number)departure.get("available_seats")).intValue() < customers.size())
            throw new IllegalArgumentException("Chuyến đi không còn đủ chỗ");
        if (customers.isEmpty()) throw new IllegalArgumentException("Booking chưa có hành khách");
        var snapshot = snapshot(bookingId);
        long amount = snapshot != null ? snapshotAmount(snapshot)
                : calculateAmount(customers, tourPrice(departure.get("tour_id")));
        BigDecimal expected;
        try { expected = expectedAmount == null ? null : new BigDecimal(expectedAmount); }
        catch (NumberFormatException malformed) { throw new IllegalArgumentException("Số tiền xác nhận không hợp lệ"); }
        if (expected == null || expected.compareTo(BigDecimal.valueOf(amount)) != 0)
            throw new IllegalArgumentException("Giá hoặc hành khách đã thay đổi; hãy tải lại hóa đơn");
        // The departure/booking rows are locked above, so this check is race-free across providers.
        String otherProviderTable = "momo".equals(provider) ? "vnpay_transaction" : "momo_transaction";
        if (jdbc.queryForObject("SELECT COUNT(*) FROM " + otherProviderTable
                + " WHERE booking_id=? AND status='PENDING' AND expires_at>?", Integer.class, bookingId, now) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Booking đang có giao dịch thanh toán khác chờ xử lý; hãy hoàn tất hoặc chờ hết hạn");
        }
        LocalDateTime expires = now.plusMinutes(15);
        if (hold != null && databaseDateTime(hold.get("expires_at")).isBefore(expires)) expires = databaseDateTime(hold.get("expires_at"));
        if (databaseDateTime(departure.get("departure_date")).isBefore(expires)) expires = databaseDateTime(departure.get("departure_date"));
        return new Checkout(bookingId, ((Number)b.get("tour_departure_id")).intValue(), amount, now, expires);
    }

    static LocalDateTime databaseDateTime(Object value) {
        if (value instanceof LocalDateTime date) return date;
        if (value instanceof java.sql.Timestamp date) return date.toLocalDateTime();
        throw new IllegalStateException("Unexpected database date type: " + (value == null ? "null" : value.getClass().getName()));
    }

    private static final int PAYMENT_INFLIGHT_GRACE_MINUTES = 5;

    private boolean paymentInFlight(int bookingId) {
        // Mirrors booking_service.ReservationExpiryService: a just-expired checkout may still be
        // confirmed by an IPN in transit, so keep its seats during the grace window.
        LocalDateTime grace = LocalDateTime.now(clock).minusMinutes(PAYMENT_INFLIGHT_GRACE_MINUTES);
        return jdbc.queryForObject("""
                SELECT (SELECT COUNT(*) FROM vnpay_transaction WHERE booking_id=? AND status='PENDING' AND expires_at>?)
                     + (SELECT COUNT(*) FROM momo_transaction WHERE booking_id=? AND status='PENDING' AND expires_at>?)
                """, Integer.class, bookingId, grace, bookingId, grace) > 0;
    }
    private boolean paid(int bookingId) {        return jdbc.queryForObject("SELECT COUNT(*) FROM invoice WHERE booking_id=? AND status='PAID'", Integer.class, bookingId) > 0
                || jdbc.queryForObject("SELECT COUNT(*) FROM payment WHERE booking_id=? AND status='PAID'", Integer.class, bookingId) > 0
                || jdbc.queryForObject("SELECT COUNT(*) FROM vnpay_transaction WHERE booking_id=? AND status='PAID'",Integer.class,bookingId)>0
                || jdbc.queryForObject("SELECT COUNT(*) FROM momo_transaction WHERE booking_id=? AND status='PAID'",Integer.class,bookingId)>0;
    }
    private Map<String,Object> reservation(int bookingId) {
        var rows = jdbc.queryForList("SELECT * FROM booking_reservation WHERE booking_id=? FOR UPDATE", bookingId);
        return rows.isEmpty() ? null : rows.getFirst(); // legacy bookings were never held
    }
    private void validateHold(Map<String,Object> hold, Map<String,Object> b, int people, LocalDateTime now) {
        if (!List.of("HELD","CHECKOUT").contains(hold.get("status")) || !databaseDateTime(hold.get("expires_at")).isAfter(now))
            throw new IllegalArgumentException("Booking đã hết hạn giữ chỗ; vui lòng đặt lại");
        if (((Number)hold.get("seats")).intValue() != people || !hold.get("departure_id").equals(b.get("tour_departure_id")))
            throw new IllegalArgumentException("Số khách hoặc lịch giữ chỗ thay đổi; cần kiểm tra");
    }
    /** Records a provider payment that arrived after checkout closed; shared with the MoMo flow. */
    String reconcile(String ref, int bookingId, long amount, LocalDateTime paidAt, String reason) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM payment_reconciliation WHERE txn_ref=?", Integer.class, ref) == 0) {
            jdbc.update("INSERT INTO payment_reconciliation (txn_ref,booking_id,reason,provider_paid_at,amount_vnd,created_at) VALUES (?,?,?,?,?,?)",
                    ref, bookingId, reason, paidAt, amount, LocalDateTime.now(clock));
        jdbc.update("UPDATE tour_departure SET version=version+1 WHERE id=(SELECT tour_departure_id FROM booking WHERE id=?)",bookingId);
        }
        return "reconciliation";
    }
    public void releaseCheckout(int bookingId, Map<String,Object> hold) {
        if (hold == null || !"CHECKOUT".equals(hold.get("status"))) return;
        // A stale failure must not expire the hold while another checkout is still payable.
        if (paymentInFlight(bookingId)) return;
        var schedules=jdbc.queryForList("SELECT * FROM tour_departure WHERE id=?",hold.get("departure_id"));
        if (!schedules.isEmpty() && java.util.List.of("SUSPENDED","CANCELLED").contains(String.valueOf(schedules.getFirst().get("operational_status")))) return;
        if(paid(bookingId) || jdbc.queryForObject("SELECT COUNT(*) FROM payment_reconciliation WHERE booking_id=? AND status='OPEN'",Integer.class,bookingId)>0) return;
        int seats=((Number)hold.get("seats")).intValue();
        LocalDateTime now = LocalDateTime.now(clock);
        // A failed checkout must always close the hold. The hold decremented available_seats by
        // exactly `seats`, so credit that same amount back even when passenger rows disagree;
        // refusing to release would leak capacity forever while an IPN could still be rejected.
        if (seats<=0) {
            log.warn("Checkout release found no held seats for booking {}: held={}", bookingId, seats);
        } else if (!schedules.isEmpty()) {
            // available_seats is SMALLINT, so legacy rows without capacity cap at 32767.
            jdbc.update("UPDATE tour_departure SET available_seats=LEAST(COALESCE(capacity,32767),available_seats+?),version=version+1 WHERE id=?", seats, hold.get("departure_id"));
        }
        int changed = jdbc.update("UPDATE booking SET status='EXPIRED' WHERE id=? AND status IN ('PENDING_PAYMENT','CONFIRMED')", bookingId);
        jdbc.update("UPDATE booking_reservation SET status='EXPIRED',cancel_reason='CHECKOUT_FAILED',updated_at=? WHERE booking_id=? AND status='CHECKOUT'", now, bookingId);
        if (changed == 1) mail.expired(bookingId, now);
    }

    /** Returns success/failed/duplicate. Signature verification must precede this method. */
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED,timeout=10)
    public String settle(Map<String, String> fields) {
        String ref = fields.get("vnp_TxnRef");
        var found = jdbc.queryForList("SELECT booking_id FROM vnpay_transaction WHERE txn_ref=?", ref);
        if (found.isEmpty()) throw new VnPayRejection("01", "Không tìm thấy giao dịch");
        int bookingId = ((Number)found.getFirst().get("booking_id")).intValue();
        // Lock departure then booking in both prepare and settle, serializing all seat writers.
        var locked = lockForSettlement(bookingId);
        var tx = jdbc.queryForMap("SELECT * FROM vnpay_transaction WHERE txn_ref=? FOR UPDATE", ref);
        long expected = ((Number)tx.get("amount_vnd")).longValue();
        long received;
        try { received = Long.parseLong(String.valueOf(fields.get("vnp_Amount"))); }
        catch (NumberFormatException invalid) { throw new VnPayRejection("04", "Số tiền giao dịch không hợp lệ"); }
        if (received != Math.multiplyExact(expected, 100L))
            throw new VnPayRejection("04", "Số tiền giao dịch không khớp");
        boolean success = "00".equals(fields.get("vnp_ResponseCode")) && "00".equals(fields.get("vnp_TransactionStatus"));
        if ("PAID".equals(tx.get("status"))) return "duplicate";
        if ("REFUNDED".equals(tx.get("status"))) return "refunded";
        if (jdbc.queryForObject("SELECT COUNT(*) FROM payment_reconciliation WHERE txn_ref=? AND status='OPEN'",Integer.class,ref)>0) return "reconciliation";
        if (!success) {
            String code = String.valueOf(fields.get("vnp_ResponseCode"));
            if (List.of("07", "09").contains(code)) {
                String transactionNo = fields.get("vnp_TransactionNo");
                if (transactionNo != null && !transactionNo.isBlank())
                    jdbc.update("UPDATE vnpay_transaction SET transaction_no=COALESCE(transaction_no,?) WHERE txn_ref=?",
                            transactionNo, ref);
                if ("07".equals(code)) {
                    // 07 = fraud-suspected: the provider may already have captured the money.
                    // Keep the hold and open a manual case, preserving the provider reference so
                    // the booking cannot be resold or closed as unpaid without evidence.
                    LocalDateTime paidAt;
                    try { paidAt = LocalDateTime.parse(fields.get("vnp_PayDate"), java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss")); }
                    catch (java.time.format.DateTimeParseException invalid) { paidAt = LocalDateTime.now(clock).withNano(0); }
                    return reconcile(ref, bookingId, expected, paidAt, "PROVIDER_FRAUD_SUSPECTED");
                }
                // 09 = card not registered for Internet Banking; no money moved, so close the
                // checkout and return the seats instead of leaving the transaction pending.
                jdbc.update("UPDATE vnpay_transaction SET status='FAILED' WHERE txn_ref=?", ref);
                releaseCheckout(bookingId, locked.hold());
                return "failed";
            }
            if("CLOSED_UNPAID".equals(tx.get("status")))return "failed";
            jdbc.update("UPDATE vnpay_transaction SET status='FAILED' WHERE txn_ref=?", ref);
            releaseCheckout(bookingId, locked.hold());
            return "failed";
        }
        LocalDateTime paidAt;
        try { paidAt = LocalDateTime.parse(fields.get("vnp_PayDate"), java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss")); }
        catch (java.time.format.DateTimeParseException invalid) { throw new VnPayRejection("99", "Thời gian thanh toán không hợp lệ"); }
        if("CLOSED_UNPAID".equals(tx.get("status"))) {
            jdbc.update("UPDATE vnpay_transaction SET status='PENDING',transaction_no=? WHERE txn_ref=?",fields.get("vnp_TransactionNo"),ref);
            return reconcile(ref,bookingId,expected,paidAt,"LATE_PAYMENT_AFTER_MANUAL_UNPAID");
        }
        var created = databaseDateTime(tx.get("created_at"));
        var expires = databaseDateTime(tx.get("expires_at"));
        String result = finalizePayment(locked, ((Number)tx.get("account_id")).intValue(), expected, created, expires, paidAt, 1, "vnpay", ref);
        if (!"success".equals(result)) return result;
        jdbc.update("UPDATE vnpay_transaction SET status='PAID',transaction_no=? WHERE txn_ref=?", fields.get("vnp_TransactionNo"), ref);
        return "success";
    }

    /** Lock departure + booking + hold in the fixed order; shared by every provider settlement. */
    public LockedSettlement lockForSettlement(int bookingId) {
        var b = booking(bookingId);
        var hold = reservation(bookingId);
        return new LockedSettlement(b, hold);
    }

    /**
     * Shared post-payment settlement: re-validate ownership/state/time, then write the invoice and
     * payment once, confirm the booking and release the hold. Returns "success" or "reconciliation".
     */
    public String finalizePayment(LockedSettlement locked, int accountId, long amountVnd,
            LocalDateTime createdAt, LocalDateTime expiresAt, LocalDateTime paidAt,
            int payMethodId, String provider, String txnRef) {
        var b = locked.booking();
        int bookingId = ((Number)b.get("id")).intValue();
        var hold = locked.hold();
        if (((Number)b.get("account_id")).intValue() != accountId)
            return reconcile(txnRef, bookingId, amountVnd, paidAt, "BOOKING_OWNER_CHANGED");
        if (!payable(b.get("status"))) return reconcile(txnRef, bookingId, amountVnd, paidAt, "BOOKING_CANCELLED_OR_EXPIRED");
        // A previously settled booking must not create a second invoice or consume seats again.
        if (paid(bookingId)) return reconcile(txnRef, bookingId, amountVnd, paidAt, "ANOTHER_PAYMENT_EXISTS");
        if (paidAt.isBefore(createdAt.minusSeconds(1)) || paidAt.isAfter(expiresAt))
            return reconcile(txnRef, bookingId, amountVnd, paidAt, "PROVIDER_TIME_OUTSIDE_CHECKOUT");
        var departure = jdbc.queryForMap("SELECT * FROM tour_departure WHERE id=? FOR UPDATE", b.get("tour_departure_id"));
        if (java.util.List.of("SUSPENDED","CANCELLED").contains(String.valueOf(departure.get("operational_status"))))
            return reconcile(txnRef, bookingId, amountVnd, paidAt, "DEPARTURE_SUSPENDED");
        int people = jdbc.queryForObject("SELECT COUNT(*) FROM customer WHERE booking_id=?", Integer.class, bookingId);
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        if (!databaseDateTime(departure.get("departure_date")).isAfter(now))
            return reconcile(txnRef, bookingId, amountVnd, paidAt, "TOUR_ALREADY_DEPARTED");
        if (hold != null) {
            // The provider says the customer paid inside the checkout window, even if the IPN
            // arrived after it; validate the hold against paidAt so a timely payment is not
            // reconciled away after its seats were already released.
            try { validateHold(hold, b, people, paidAt); }
            catch (IllegalArgumentException invalid) { return reconcile(txnRef, bookingId, amountVnd, paidAt, "RESERVATION_NO_LONGER_VALID"); }
            if (!databaseDateTime(hold.get("departure_at")).equals(databaseDateTime(departure.get("departure_date"))))
                return reconcile(txnRef, bookingId, amountVnd, paidAt, "DEPARTURE_CHANGED");
        } else {
            if (people <= 0 || ((Number)departure.get("available_seats")).intValue() < people)
                return reconcile(txnRef, bookingId, amountVnd, paidAt, "INSUFFICIENT_SEATS");
            jdbc.update("UPDATE tour_departure SET available_seats=available_seats-?,version=version+1 WHERE id=?", people, b.get("tour_departure_id"));
        }
        var invoices = jdbc.queryForList("SELECT id FROM invoice WHERE booking_id=?", bookingId);
        if (invoices.isEmpty()) {
            jdbc.update("INSERT INTO invoice (account_id,booking_id,day_of_pay,pay_method_id,status,total_booking_tour_expense) VALUES (?,?,?,?,'PAID',?)", accountId, bookingId, paidAt, payMethodId, amountVnd);
        } else {
            jdbc.update("UPDATE invoice SET day_of_pay=?,pay_method_id=?,status='PAID',total_booking_tour_expense=? WHERE booking_id=?", paidAt, payMethodId, amountVnd, bookingId);
        }
        var payments = jdbc.queryForList("SELECT id FROM payment WHERE booking_id=?", bookingId);
        if (payments.isEmpty()) {
            jdbc.update("INSERT INTO payment (account_id,booking_id,day_of_pay,pay_method_id,status,total_booking_tour_expense) VALUES (?,?,?,?,'PAID',?)", accountId, bookingId, paidAt, payMethodId, amountVnd);
        } else {
            jdbc.update("UPDATE payment SET day_of_pay=?,pay_method_id=?,status='PAID',total_booking_tour_expense=? WHERE booking_id=?", paidAt, payMethodId, amountVnd, bookingId);
        }
        jdbc.update("UPDATE booking SET status='CONFIRMED' WHERE id=? AND status='PENDING_PAYMENT'", bookingId);
        if (hold != null) {
            jdbc.update("UPDATE booking_reservation SET status='PAID',updated_at=? WHERE booking_id=?", now, bookingId);
            jdbc.update("UPDATE tour_departure SET version=version+1 WHERE id=?",b.get("tour_departure_id"));
        }
        mail.receipt(bookingId, txnRef, amountVnd, paidAt, now, provider);
        return "success";
    }
}
