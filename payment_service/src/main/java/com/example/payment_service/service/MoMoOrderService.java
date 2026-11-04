package com.example.payment_service.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

/**
 * MoMo settlement. Reuses {@link VnPayOrderService} for the shared checkout/settlement guards so the
 * two providers settle identically; only the transaction table and callback shapes differ.
 */
@Service
@RequiredArgsConstructor
public class MoMoOrderService {
    private final VnPayOrderService shared;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public record Order(String orderId, String requestId, long amountVnd, LocalDateTime createdAt, LocalDateTime expiresAt, boolean reused) {}

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED, timeout = 10)
    public Order prepare(int bookingId, int accountId, String expectedAmount) {
        var c = shared.beginCheckout(bookingId, accountId, expectedAmount, "momo");
        var pending = jdbc.queryForList("SELECT * FROM momo_transaction WHERE booking_id=? AND status='PENDING' AND expires_at>? ORDER BY created_at DESC LIMIT 1", bookingId, c.now());
        if (!pending.isEmpty()) {
            var p = pending.getFirst();
            if (((Number) p.get("amount_vnd")).longValue() != c.amountVnd())
                throw new IllegalArgumentException("Bảng giá đã thay đổi; hãy chờ yêu cầu thanh toán cũ hết hạn");
            return new Order(p.get("order_id").toString(), p.get("request_id").toString(), c.amountVnd(),
                    VnPayOrderService.databaseDateTime(p.get("created_at")),
                    VnPayOrderService.databaseDateTime(p.get("expires_at")), true);
        }
        String orderId = "M" + UUID.randomUUID().toString().replace("-", "").substring(0, 31);
        jdbc.update("INSERT INTO momo_transaction (order_id,request_id,booking_id,account_id,amount_vnd,status,created_at,expires_at) VALUES (?,?,?,?,?,'PENDING',?,?)",
                orderId, orderId, bookingId, accountId, c.amountVnd(), c.now(), c.expires());
        jdbc.update("UPDATE tour_departure SET version=version+1 WHERE id=?", c.departureId());
        return new Order(orderId, orderId, c.amountVnd(), c.now(), c.expires(), false);
    }

    /** Closes an order that never became payable, so the next attempt is not blocked by a stale PENDING row. */
    public void abandon(String orderId) {
        jdbc.update("UPDATE momo_transaction SET status='FAILED' WHERE order_id=? AND status='PENDING'", orderId);
    }

    /** Returns success/failed/duplicate. Signature verification must precede this method. */
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED, timeout = 10)
    public String settle(Map<String, String> fields) {
        String orderId = fields.get("orderId");
        var found = jdbc.queryForList("SELECT booking_id FROM momo_transaction WHERE order_id=?", orderId);
        if (found.isEmpty()) throw new VnPayRejection("01", "Không tìm thấy giao dịch");
        int bookingId = ((Number) found.getFirst().get("booking_id")).intValue();
        var locked = shared.lockForSettlement(bookingId);
        var tx = jdbc.queryForMap("SELECT * FROM momo_transaction WHERE order_id=? FOR UPDATE", orderId);
        long expected = ((Number) tx.get("amount_vnd")).longValue();
        long received;
        try { received = Long.parseLong(String.valueOf(fields.get("amount"))); }
        catch (NumberFormatException invalid) { throw new VnPayRejection("04", "Số tiền giao dịch không hợp lệ"); }
        if (received != expected) throw new VnPayRejection("04", "Số tiền giao dịch không khớp");
        boolean success = "0".equals(fields.get("resultCode"));
        if ("PAID".equals(tx.get("status"))) return "duplicate";
        if ("REFUNDED".equals(tx.get("status"))) return "refunded";
        if (jdbc.queryForObject("SELECT COUNT(*) FROM payment_reconciliation WHERE txn_ref=? AND status='OPEN'", Integer.class, orderId) > 0)
            return "reconciliation";
        if (!success) {
            if ("CLOSED_UNPAID".equals(tx.get("status"))) return "failed";
            // 9000 means MoMo accepted the order but the wallet confirmation is still pending;
            // releasing the seats here would sell them again if the user confirms afterwards.
            // Record what MoMo reported and keep the row PENDING so its retry IPN can still settle it.
            if ("9000".equals(String.valueOf(fields.get("resultCode")))) {
                jdbc.update("UPDATE momo_transaction SET trans_id=?,result_code=? WHERE order_id=? AND status='PENDING'",
                        parseLong(fields.get("transId")), parseInt(fields.get("resultCode")), orderId);
                return "pending";
            }
            jdbc.update("UPDATE momo_transaction SET status='FAILED',result_code=? WHERE order_id=?", parseInt(fields.get("resultCode")), orderId);
            shared.releaseCheckout(bookingId, locked.hold());
            return "failed";
        }
        LocalDateTime paidAt = parseResponseTime(fields.get("responseTime"));
        if ("CLOSED_UNPAID".equals(tx.get("status"))) {
            jdbc.update("UPDATE momo_transaction SET status='PENDING',trans_id=?,result_code=? WHERE order_id=?",
                    parseLong(fields.get("transId")), parseInt(fields.get("resultCode")), orderId);
            return shared.reconcile(orderId, bookingId, expected, paidAt, "LATE_PAYMENT_AFTER_MANUAL_UNPAID");
        }
        var created = VnPayOrderService.databaseDateTime(tx.get("created_at"));
        var expires = VnPayOrderService.databaseDateTime(tx.get("expires_at"));
        String result = shared.finalizePayment(locked, ((Number) tx.get("account_id")).intValue(), expected, created, expires, paidAt, 2, "momo", orderId);
        if (!"success".equals(result)) return result;
        jdbc.update("UPDATE momo_transaction SET status='PAID',trans_id=?,result_code=? WHERE order_id=?", parseLong(fields.get("transId")), parseInt(fields.get("resultCode")), orderId);
        return "success";
    }

    private LocalDateTime parseResponseTime(String value) {
        if (value == null || value.isBlank()) throw new VnPayRejection("99", "Thiếu thời gian thanh toán");
        try {
            return Instant.ofEpochMilli(Long.parseLong(value)).atZone(clock.getZone()).toLocalDateTime();
        } catch (RuntimeException invalid) {
            throw new VnPayRejection("99", "Thời gian thanh toán không hợp lệ");
        }
    }

    private static Long parseLong(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Long.parseLong(value); } catch (NumberFormatException invalid) { return null; }
    }

    private static Integer parseInt(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Integer.parseInt(value); } catch (NumberFormatException invalid) { return null; }
    }
}
