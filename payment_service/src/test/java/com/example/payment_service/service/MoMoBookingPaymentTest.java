package com.example.payment_service.service;

import com.example.payment_service.config.MoMoConfig;
import com.example.payment_service.notification.PaymentMailOutboxWriter;
import com.example.payment_service.reservation.LifecycleTestDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MoMoBookingPaymentTest {
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final MoMoConfig config = new MoMoConfig("MOMOPARTNER", "ACCESS", "secret",
            "https://test-payment.momo.vn/v2/gateway/api/create",
            "http://localhost:5173/payment/momo/callback",
            "http://localhost:8083/payment/momo/ipn");
    final Clock clock = Clock.fixed(LocalDateTime.of(2026, 11, 1, 10, 0).atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant(), ZoneId.of("Asia/Ho_Chi_Minh"));

    LifecycleTestDatabase db;
    VnPayOrderService vnpayOrders;
    MoMoOrderService orders;
    MoMoService service;

    @BeforeEach void setup() throws Exception {
        db = new LifecycleTestDatabase();
        db.jdbc.update("INSERT INTO booking (id,account_id,tour_departure_id,status,created_at) VALUES (1,7,1,'CONFIRMED',?)", LocalDateTime.of(2026, 11, 1, 8, 0));
        db.jdbc.update("INSERT INTO customer (booking_id,booking_type) VALUES (1,'ADULT'),(1,'ADULT')");
        vnpayOrders = new VnPayOrderService(db.jdbc, new PaymentMailOutboxWriter(db.jdbc, mapper), clock);
        orders = new MoMoOrderService(vnpayOrders, db.jdbc, clock);
        service = new MoMoService(orders, vnpayOrders, config, mapper, clock);
    }

    MoMoOrderService.Order prepare() {
        return db.tx.execute(tx -> orders.prepare(1, 7, "6700000"));
    }
    Map<String, String> callback(MoMoOrderService.Order order, boolean success) {
        Map<String, String> fields = new HashMap<>();
        fields.put("partnerCode", "MOMOPARTNER");
        fields.put("orderId", order.orderId());
        fields.put("requestId", order.requestId());
        fields.put("amount", Long.toString(order.amountVnd()));
        fields.put("orderInfo", "Thanh toan booking 1");
        fields.put("orderType", "momo_wallet");
        fields.put("transId", "123456789");
        fields.put("resultCode", success ? "0" : "42");
        fields.put("message", success ? "Success" : "User cancelled");
        fields.put("payType", "qr");
        fields.put("responseTime", Long.toString(order.createdAt().plusMinutes(1).atZone(clock.getZone()).toInstant().toEpochMilli()));
        fields.put("extraData", "");
        fields.put("signature", MoMoSignature.signCallback(config.accessKey(), config.secretKey(), fields));
        return fields;
    }
    int count(String table) { return db.count(table); }
    int seats() { return db.seats(); }

    @Test void preparePersistsAndReusesPendingTransaction() {
        var first = prepare();
        assertTrue(first.orderId().startsWith("M"));
        assertEquals(6_700_000L, first.amountVnd());
        assertEquals(1, count("momo_transaction"));
        assertEquals(first.orderId(), prepare().orderId());
        assertEquals(1, count("momo_transaction"));
    }

    @Test void abandonedOrderDoesNotBlockTheNextAttempt() {
        var first = prepare();
        orders.abandon(first.orderId());
        assertEquals("FAILED", db.jdbc.queryForObject(
                "SELECT status FROM momo_transaction WHERE order_id=?", String.class, first.orderId()));
        var retry = prepare();
        assertNotEquals(first.orderId(), retry.orderId());
        assertEquals(2, count("momo_transaction"));
    }

    @Test void successSettlesOnceAndReplayIsDuplicate() {
        var fields = callback(prepare(), true);
        var result = db.tx.execute(tx -> service.handlePaymentReturn(fields));
        assertEquals("success", result.getStatus());
        assertEquals(1, count("invoice")); assertEquals(1, count("payment")); assertEquals(8, seats());
        assertEquals("CONFIRMED", db.jdbc.queryForObject("SELECT status FROM booking WHERE id=1", String.class));
        assertEquals("PAID", db.jdbc.queryForObject("SELECT status FROM momo_transaction", String.class));
        assertEquals(2, db.jdbc.queryForObject("SELECT pay_method_id FROM invoice", Integer.class));
        assertTrue(db.tx.execute(tx -> service.handlePaymentReturn(fields)).isAlreadyProcessed());
        assertEquals(1, count("invoice")); assertEquals(8, seats());
    }

    @Test void wrongAmountIsRejectedWithoutSettling() {
        var fields = callback(prepare(), true);
        fields.put("amount", "9999");
        fields.put("signature", MoMoSignature.signCallback(config.accessKey(), config.secretKey(), fields));
        assertThrows(RuntimeException.class, () -> db.tx.execute(tx -> service.handlePaymentReturn(fields)));
        assertEquals(0, count("invoice")); assertEquals(10, seats());
    }

    @Test void failedPaymentDoesNotProduceInvoiceOrConsumeSeats() {
        var fields = callback(prepare(), false);
        assertEquals("failed", db.tx.execute(tx -> service.handlePaymentReturn(fields)).getStatus());
        assertEquals(0, count("invoice")); assertEquals(0, count("payment")); assertEquals(10, seats());
        assertEquals("FAILED", db.jdbc.queryForObject("SELECT status FROM momo_transaction", String.class));
    }

    @Test void successAfterManualUnpaidGoesToReconciliation() {
        var order = prepare();
        db.jdbc.update("UPDATE momo_transaction SET status='CLOSED_UNPAID' WHERE order_id=?", order.orderId());
        var fields = callback(order, true);
        assertEquals("reconciliation", db.tx.execute(tx -> orders.settle(fields)));
        assertEquals(1, count("payment_reconciliation"));
        assertEquals(0, count("invoice")); assertEquals(0, count("payment")); assertEquals(10, seats());
        assertEquals("PENDING", db.jdbc.queryForObject("SELECT status FROM momo_transaction WHERE order_id=?", String.class, order.orderId()));
        assertEquals(123456789L, db.jdbc.queryForObject("SELECT trans_id FROM momo_transaction WHERE order_id=?", Long.class, order.orderId()));
    }

    @Test void failureAfterManualUnpaidKeepsClosedState() {
        var order = prepare();
        db.jdbc.update("UPDATE momo_transaction SET status='CLOSED_UNPAID' WHERE order_id=?", order.orderId());
        assertEquals("failed", db.tx.execute(tx -> orders.settle(callback(order, false))));
        assertEquals("CLOSED_UNPAID", db.jdbc.queryForObject("SELECT status FROM momo_transaction WHERE order_id=?", String.class, order.orderId()));
        assertEquals(0, count("payment_reconciliation"));
        assertEquals(10, seats());
    }

    @Test void unconfiguredMoMoFailsFastWith503() {        var blank = new MoMoConfig("", "", "", "https://test-payment.momo.vn/v2/gateway/api/create", "", "");
        var unconfigured = new MoMoService(orders, vnpayOrders, blank, mapper, clock);
        var ex = assertThrows(ResponseStatusException.class, () -> unconfigured.createPayment(1, "6700000", 7));
        assertEquals(503, ex.getStatusCode().value());
        assertEquals(0, count("momo_transaction"));
    }
}
