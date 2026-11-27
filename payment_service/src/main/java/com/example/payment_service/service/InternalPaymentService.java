package com.example.payment_service.service;

import com.example.payment_service.dto.internal.InternalPaymentResponse;
import com.example.payment_service.entity.Payment;
import com.example.payment_service.repository.PaymentRepository;
import com.example.payment_service.outbox.OutboxPublisher;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class InternalPaymentService {

    private final PaymentRepository paymentRepository;
    private final JdbcTemplate jdbc;
    private final OutboxPublisher outbox;

    @Transactional(readOnly = true)
    public ResponseEntity<InternalPaymentResponse> getPaymentById(Integer id) {
        return paymentRepository.findById(id)
                .map(this::toInternalResponse)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Transactional(readOnly = true)
    public ResponseEntity<InternalPaymentResponse> getPaymentByBookingId(Integer bookingId) {
        return paymentRepository.findByBookingId(bookingId)
                .map(this::toInternalResponse)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Transactional(readOnly = true)
    public ResponseEntity<List<InternalPaymentResponse>> getPaymentsBatch(List<Integer> bookingIds) {
        if (bookingIds == null || bookingIds.isEmpty() || bookingIds.size() > 100 || bookingIds.stream().anyMatch(id -> id == null || id < 1)) {
            return ResponseEntity.badRequest().build();
        }
        List<Payment> payments = paymentRepository.findByBookingIdIn(bookingIds.stream().distinct().toList());
        List<InternalPaymentResponse> responses = payments.stream()
                .map(this::toInternalResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(responses);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> reportAmounts(
            LocalDate from,
            LocalDate through) {
        if (from.isAfter(through) || through.isAfter(from.plusYears(1).minusDays(1))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Khoảng báo cáo tối đa một năm");
        }
        var start = from.atStartOfDay();
        var until = through.plusDays(1).atStartOfDay();
        return jdbc.queryForList("""
                SELECT booking_id, DATE_FORMAT(day_of_pay,'%Y-%m-%d') AS day,
                    SUM(total_booking_tour_expense) AS amount, 'collected' AS metric
                FROM payment WHERE status IN ('PAID','REFUNDED') AND day_of_pay>=? AND day_of_pay<?
                GROUP BY booking_id, DATE_FORMAT(day_of_pay,'%Y-%m-%d')
                UNION ALL
                SELECT booking_id, DATE_FORMAT(created_at,'%Y-%m-%d'),
                    SUM(amount_vnd), 'reconciliation_refunds'
                FROM payment_reconciliation_event WHERE action='REFUND_RECORDED' AND created_at>=? AND created_at<?
                GROUP BY booking_id, DATE_FORMAT(created_at,'%Y-%m-%d')
                """, start, until, start, until);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> invoiceTransactions(int bookingId) {
        return jdbc.queryForList("""
                SELECT 'VNPAY' AS provider, txn_ref AS reference, CAST(transaction_no AS CHAR) AS provider_reference, status
                FROM vnpay_transaction WHERE booking_id=? AND status IN ('PAID','REFUNDED')
                UNION ALL
                SELECT 'MOMO', order_id, CAST(trans_id AS CHAR), status
                FROM momo_transaction WHERE booking_id=? AND status IN ('PAID','REFUNDED')
                """, bookingId, bookingId);
    }

    @Transactional
    public Map<String, Object> refund(Integer bookingId, Long amountVnd, String reference) {
        if (amountVnd <= 0 || reference.isBlank() || reference.length() > 191)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chứng từ hoàn tiền không hợp lệ");
        var payments = jdbc.queryForList("""
                SELECT account_id,status,total_booking_tour_expense,day_of_pay,pay_method_id
                FROM payment WHERE booking_id=? AND status IN ('PAID','REFUNDED') FOR UPDATE
                """, bookingId);
        if (payments.size() != 1)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cần đúng một khoản thanh toán để ghi nhận hoàn toàn bộ");
        var payment = payments.getFirst();
        if (new java.math.BigDecimal(payment.get("total_booking_tour_expense").toString()).compareTo(java.math.BigDecimal.valueOf(amountVnd)) != 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Số tiền hoàn không khớp khoản đã thu");
        String aggregate = String.valueOf(bookingId);
        if ("REFUNDED".equals(payment.get("status"))) {
            Integer repeats = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM payment_outbox WHERE aggregate_id=? AND event_type='payment.refunded.v1'
                    AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.refundReference'))=?
                    """, Integer.class, aggregate, reference);
            if (repeats == null || repeats == 0)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Khoản thanh toán đã có hồ sơ hoàn khác");
        } else {
            jdbc.update("UPDATE payment SET status='REFUNDED' WHERE booking_id=? AND status='PAID'", bookingId);
            jdbc.update("UPDATE vnpay_transaction SET status='REFUNDED' WHERE booking_id=? AND status='PAID'", bookingId);
            jdbc.update("UPDATE momo_transaction SET status='REFUNDED' WHERE booking_id=? AND status='PAID'", bookingId);
            Object paidAt = payment.get("day_of_pay");
            LocalDateTime paidTime = paidAt instanceof java.sql.Timestamp timestamp
                    ? timestamp.toLocalDateTime() : (LocalDateTime) paidAt;
            outbox.writeToOutbox("payment.refunded.v1", "Payment", aggregate, aggregate, aggregate, Map.of(
                    "bookingId", bookingId, "accountId", payment.get("account_id"), "amountVnd", amountVnd,
                    "payMethodId", payment.get("pay_method_id"), "paidAt", paidTime.toString(),
                    "refundReference", reference, "status", "REFUNDED"));
        }
        return Map.of("success", true, "bookingId", bookingId, "amountVnd", amountVnd);
    }

    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> getOpenReconciliationCount(Long accountId) {
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM payment_reconciliation pr
            WHERE pr.status = 'OPEN'
              AND (
                EXISTS (SELECT 1 FROM vnpay_transaction v WHERE v.txn_ref = pr.txn_ref AND v.account_id = ?)
                OR EXISTS (SELECT 1 FROM momo_transaction m WHERE m.order_id = pr.txn_ref AND m.account_id = ?)
                OR EXISTS (SELECT 1 FROM payment p WHERE p.booking_id = pr.booking_id AND p.account_id = ?)
              )
            """, Integer.class, accountId, accountId, accountId);
        return ResponseEntity.ok(Map.of("count", count != null ? count : 0));
    }

    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> getPendingTransactions(Long accountId) {
        LocalDateTime current = LocalDateTime.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh"));
        Integer vnpayPending = jdbc.queryForObject(
                "SELECT COUNT(*) FROM vnpay_transaction WHERE account_id = ? AND status = 'PENDING' AND expires_at > ?",
                Integer.class, accountId, current);
        Integer momoPending = jdbc.queryForObject(
                "SELECT COUNT(*) FROM momo_transaction WHERE account_id = ? AND status = 'PENDING' AND expires_at > ?",
                Integer.class, accountId, current);
        return ResponseEntity.ok(Map.of(
                "vnpayPending", vnpayPending != null ? vnpayPending : 0,
                "momoPending", momoPending != null ? momoPending : 0));
    }

    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> hasUserHistory(Long accountId) {
        Integer payments = jdbc.queryForObject("SELECT COUNT(*) FROM payment WHERE account_id = ?", Integer.class, accountId);
        Integer reconEvents = jdbc.queryForObject("SELECT COUNT(*) FROM payment_reconciliation_event WHERE actor_id = ?", Integer.class, accountId);
        boolean hasHistory = (payments != null && payments > 0) || (reconEvents != null && reconEvents > 0);
        return ResponseEntity.ok(Map.of("hasHistory", hasHistory));
    }

    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> getBookingPaymentEvidence(Integer bookingId) {
        LocalDateTime now = LocalDateTime.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh"));
        LocalDateTime grace = now.minusMinutes(5);
        int paidPayment = countSql("SELECT COUNT(*) FROM payment WHERE booking_id = ? AND status = 'PAID'", bookingId);
        int paidVnpay = countSql("SELECT COUNT(*) FROM vnpay_transaction WHERE booking_id = ? AND status = 'PAID'", bookingId);
        int paidMomo = countSql("SELECT COUNT(*) FROM momo_transaction WHERE booking_id = ? AND status = 'PAID'", bookingId);
        int openRecon = countSql("SELECT COUNT(*) FROM payment_reconciliation WHERE booking_id = ? AND status = 'OPEN'", bookingId);
        int pendingVnpay = countSql("SELECT COUNT(*) FROM vnpay_transaction WHERE booking_id = ? AND status = 'PENDING' AND expires_at > ?", bookingId, grace);
        int pendingMomo = countSql("SELECT COUNT(*) FROM momo_transaction WHERE booking_id = ? AND status = 'PENDING' AND expires_at > ?", bookingId, grace);
        boolean paidEvidence = paidPayment > 0 || paidVnpay > 0 || paidMomo > 0 || openRecon > 0;
        boolean inFlight = pendingVnpay > 0 || pendingMomo > 0;
        return ResponseEntity.ok(Map.of(
                "paidPayment", paidPayment > 0,
                "paidProvider", (paidVnpay + paidMomo) > 0,
                "openReconciliation", openRecon > 0,
                "inFlight", inFlight,
                "paidEvidence", paidEvidence,
                "evidenceCount", paidPayment + paidVnpay + paidMomo + openRecon + pendingVnpay + pendingMomo
        ));
    }

    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> hasSettledReference(
            Integer bookingId,
            Long accountId,
            String transactionRef,
            Long amountVnd) {
        int vnpay = countSql("SELECT COUNT(*) FROM vnpay_transaction WHERE booking_id = ? AND account_id = ? AND txn_ref = ? AND status = 'PAID' AND amount_vnd = ?",
                bookingId, accountId, transactionRef, amountVnd);
        int momo = countSql("SELECT COUNT(*) FROM momo_transaction WHERE booking_id = ? AND account_id = ? AND order_id = ? AND status = 'PAID' AND amount_vnd = ?",
                bookingId, accountId, transactionRef, amountVnd);
        return ResponseEntity.ok(Map.of("settled", (vnpay + momo) == 1));
    }

    private int countSql(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n != null ? n : 0;
    }

    private InternalPaymentResponse toInternalResponse(Payment payment) {
        return InternalPaymentResponse.builder()
                .id(payment.getId())
                .accountId(payment.getAccountId())
                .bookingId(payment.getBookingId())
                .dayOfPay(payment.getDayOfPay())
                .payMethodId(payment.getPayMethodId())
                .status(payment.getStatus().name())
                .totalBookingTourExpense(payment.getTotalBookingTourExpense())
                .build();
    }
}
