package com.example.booking_service.change;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Component
public class ChangeLedger {

    private static final List<String> ACTIVE_HOLD_STATUSES = List.of("PAID", "HELD", "CHECKOUT");

    private final JdbcTemplate jdbc;

    public ChangeLedger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public static void require(boolean condition, String message) {
        if (!condition) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, message);
        }
    }

    /** A row the caller asked for by id either is or is not there; that is 404, not a conflict. */
    public static void requireFound(boolean condition, String message) {
        if (!condition) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, message);
        }
    }

    public static int n(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).intValue();
    }

    public static long v(Map<String, Object> row) {
        return ((Number) row.get("version")).longValue();
    }

    public static LocalDateTime date(Object value) {
        return value instanceof LocalDateTime time ? time : ((Timestamp) value).toLocalDateTime();
    }

    public Map<String, Object> booking(int id) {
        var rows = jdbc.queryForList("SELECT * FROM booking WHERE id=?", id);
        requireFound(rows.size() == 1, "Không tìm thấy booking");
        return rows.getFirst();
    }

    public Map<Integer, Map<String, Object>> lockDepartures(int... ids) {
        return departures(true, ids);
    }

    /** Read-only twin of {@link #lockDepartures}: previews must not block checkouts on the departure. */
    public Map<Integer, Map<String, Object>> readDepartures(int... ids) {
        return departures(false, ids);
    }

    private Map<Integer, Map<String, Object>> departures(boolean forUpdate, int... ids) {
        var departures = new TreeMap<Integer, Map<String, Object>>();
        for (int id : Arrays.stream(ids).distinct().sorted().toArray()) {
            var rows = jdbc.queryForList("SELECT * FROM tour_departure WHERE id=?"
                    + (forUpdate ? " FOR UPDATE" : ""), id);
            requireFound(rows.size() == 1, "Không tìm thấy lịch khởi hành");
            departures.put(id, rows.getFirst());
        }
        return departures;
    }

    public Map<String, Object> lockBooking(int id, int source) {
        var rows = jdbc.queryForList("SELECT * FROM booking WHERE id=? FOR UPDATE", id);
        requireFound(rows.size() == 1, "Không tìm thấy booking");
        require(n(rows.getFirst(), "tour_departure_id") == source,
                "Booking đã chuyển lịch; tải lại dữ liệu");
        return rows.getFirst();
    }

    public Map<String, Object> reservation(int booking) {
        var rows = jdbc.queryForList("SELECT * FROM booking_reservation WHERE booking_id=? FOR UPDATE", booking);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public void clearTransactions(int booking) {
        require(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM payment_reconciliation WHERE booking_id=? AND status='OPEN'",
                        Integer.class, booking) == 0,
                "Booking đang cần đối soát");
        require(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM vnpay_transaction WHERE booking_id=? AND status='PENDING'",
                        Integer.class, booking) == 0,
                "Giao dịch VNPay chưa có kết quả rõ ràng; hết hạn link chưa đủ để chuyển lịch");
        require(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM momo_transaction WHERE booking_id=? AND status='PENDING'",
                        Integer.class, booking) == 0,
                "Giao dịch MoMo chưa có kết quả rõ ràng; hết hạn link chưa đủ để chuyển lịch");
    }

    public BigDecimal paid(int booking, int owner) {
        var invoiceAmount = paidDocument("invoice", booking, owner);
        var paymentAmount = paidDocument("payment", booking, owner);
        require(invoiceAmount.signum() > 0 && invoiceAmount.compareTo(paymentAmount) == 0,
                "Tiền hóa đơn và payment không khớp");

        // Both providers settle into the same documents, but a stray provider row would still
        // mean the money story is inconsistent; rescheduling must stop for manual review.
        require(paidProvider("vnpay_transaction", booking, owner, invoiceAmount),
                "Giao dịch VNPay PAID cần đối soát");
        require(paidProvider("momo_transaction", booking, owner, invoiceAmount),
                "Giao dịch MoMo PAID cần đối soát");
        return invoiceAmount;
    }

    private boolean paidProvider(String table, int booking, int owner, BigDecimal amount) {
        var rows = jdbc.queryForList(
                "SELECT account_id,amount_vnd FROM " + table + " WHERE booking_id=? AND status='PAID'",
                booking);
        return rows.size() <= 1 && rows.stream()
                .allMatch(row -> n(row, "account_id") == owner
                        && new BigDecimal(row.get("amount_vnd").toString()).compareTo(amount) == 0);
    }

    private BigDecimal paidDocument(String table, int booking, int owner) {
        var rows = jdbc.queryForList(
                "SELECT account_id,total_booking_tour_expense FROM " + table
                        + " WHERE booking_id=? AND status='PAID'",
                booking);
        require(rows.size() == 1 && n(rows.getFirst(), "account_id") == owner,
                "Chứng từ thanh toán chưa đủ hoặc bị trùng; cần đối soát");
        return new BigDecimal(rows.getFirst().get("total_booking_tour_expense").toString());
    }

    public boolean hasPaid(int booking) {
        return jdbc.queryForObject("""
                SELECT (SELECT COUNT(*) FROM invoice WHERE booking_id=? AND status='PAID')
                     + (SELECT COUNT(*) FROM payment WHERE booking_id=? AND status='PAID')
                     + (SELECT COUNT(*) FROM vnpay_transaction WHERE booking_id=? AND status='PAID')
                     + (SELECT COUNT(*) FROM momo_transaction WHERE booking_id=? AND status='PAID')
                """, Integer.class, booking, booking, booking, booking) > 0;
    }

    public int people(int booking) {
        int count = jdbc.queryForObject("SELECT COUNT(*) FROM customer WHERE booking_id=?", Integer.class, booking);
        require(count > 0, "Booking thiếu hành khách");
        return count;
    }

    public int assertLedger(Map<String, Object> departure) {
        require(departure.get("capacity") != null, "Admin cần xác nhận tổng sức chứa trước khi chuyển lịch");
        int occupied = 0;

        var bookings = jdbc.queryForList("SELECT * FROM booking WHERE tour_departure_id=? ORDER BY id",
                n(departure, "id"));
        for (var booking : bookings) {
            int bookingId = n(booking, "id");
            boolean money = hasPaid(bookingId);
            boolean refunded = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM booking_change WHERE booking_id=? AND status='REFUNDED'",
                    Integer.class, bookingId) > 0;
            var holds = jdbc.queryForList("SELECT * FROM booking_reservation WHERE booking_id=?", bookingId);

            if (holds.isEmpty()) {
                if (money && !refunded) {
                    require("CONFIRMED".equals(booking.get("status")), "Booking hủy còn chứng từ chưa xử lý");
                    occupied += people(bookingId);
                }
                continue;
            }

            var hold = holds.getFirst();
            String status = String.valueOf(hold.get("status"));
            if (ACTIVE_HOLD_STATUSES.contains(status)) {
                boolean paidHold = "PAID".equals(status);
                // An unpaid hold legitimately belongs to PENDING_PAYMENT; only money may assert CONFIRMED.
                require(("CONFIRMED".equals(booking.get("status"))
                                || (!paidHold && "PENDING_PAYMENT".equals(booking.get("status"))))
                                && n(hold, "departure_id") == n(departure, "id")
                                && date(hold.get("departure_at")).equals(date(departure.get("departure_date")))
                                && n(hold, "seats") == people(bookingId),
                        "Dữ liệu giữ chỗ không khớp; cần kiểm tra");
                require(paidHold == money, "Giữ chỗ và thanh toán mâu thuẫn");
                occupied += n(hold, "seats");
            } else {
                require(!money || refunded, "Chỗ đã trả nhưng tiền chưa được giải quyết");
            }
        }

        require(occupied + n(departure, "available_seats") == n(departure, "capacity"),
                "Số chỗ không cân bằng; không tự sửa trong lúc chuyển lịch");
        return occupied;
    }

    public void compatible(Map<String, Object> source, Map<String, Object> target, LocalDateTime now) {
        require(n(source, "id") != n(target, "id") && n(source, "tour_id") == n(target, "tour_id"),
                "Chỉ chuyển sang lịch khác của cùng tour");
        require(date(target.get("departure_date")).isAfter(now), "Lịch mới đã khởi hành");
        require(Duration.between(date(source.get("departure_date")), date(source.get("return_date")))
                        .equals(Duration.between(date(target.get("departure_date")), date(target.get("return_date")))),
                "Hai lịch khác thời lượng; cần hỗ trợ thủ công");
    }

    public void samePrice(int booking, Map<String, Object> source, BigDecimal amount) {
        var prices = jdbc.queryForMap(
                "SELECT p.* FROM prices p JOIN tour t ON t.price_id=p.id WHERE t.id=?",
                n(source, "tour_id"));
        BigDecimal total = BigDecimal.ZERO;
        var customers = jdbc.queryForList("SELECT booking_type FROM customer WHERE booking_id=?", booking);
        for (var customer : customers) {
            String column = priceColumn(String.valueOf(customer.get("booking_type")));
            total = total.add(new BigDecimal(prices.get(column).toString()));
        }
        require(total.compareTo(amount) == 0,
                "Giá hiện tại khác số tiền đã thanh toán; cần thỏa thuận chênh lệch thủ công");
    }

    private static String priceColumn(String bookingType) {
        return switch (bookingType) {
            case "ADULT" -> "adult_price";
            case "CHILD" -> "child_price";
            case "TODDLER" -> "toddler_price";
            case "INFANT" -> "infant_price";
            default -> throw new IllegalArgumentException("Loại khách không hợp lệ");
        };
    }
}
