package com.example.payment_service.reservation;

import com.example.payment_service.service.*;
import com.example.payment_service.notification.PaymentMailOutboxWriter;
import com.example.payment_service.config.VnPayConfig;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ReservedVnPayTest {
    final VnPayConfig config = new VnPayConfig("synthetic-test-hash-secret", "SYNTHETIC_TMN",
            "http://localhost:5173/payment/vnpay/callback",
            "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html");

    LifecycleTestDatabase db;
    LifecycleTestDatabase.MutableClock clock;
    VnPayOrderService orders;
    VnPayService service;
    @BeforeEach void setup() throws Exception {
        db = new LifecycleTestDatabase(); clock = new LifecycleTestDatabase.MutableClock(LocalDateTime.of(2026,11,1,10,0));
        orders = new VnPayOrderService(db.jdbc, new PaymentMailOutboxWriter(db.jdbc, JsonMapper.builder().findAndAddModules().build()), clock);
        service = new VnPayService(orders, config);
        db.jdbc.update("INSERT INTO booking VALUES (1,7,1,'CONFIRMED',?)", LocalDateTime.now(clock));
        db.jdbc.update("INSERT INTO customer (booking_id,booking_type) VALUES (1,'ADULT')");
        db.jdbc.update("UPDATE tour_departure SET available_seats=9");
        db.jdbc.update("""
            INSERT INTO booking_reservation (booking_id,departure_id,departure_at,seats,status,expires_at,created_at,updated_at)
            VALUES (1,1,?,1,'HELD',?,?,?)
            """, LocalDateTime.of(2026,11,20,6,0), LocalDateTime.of(2026,11,7,8,0), LocalDateTime.now(clock), LocalDateTime.now(clock));
    }
    VnPayOrderService.Order prepare() { return db.tx.execute(tx -> orders.prepare(1,7,"3350000")); }
    Map<String,String> callback(VnPayOrderService.Order order, boolean success) {
        var fields = new HashMap<String,String>();
        fields.put("vnp_TxnRef",order.txnRef()); fields.put("vnp_TmnCode", config.tmnCode());
        fields.put("vnp_Amount",String.valueOf(order.amountVnd()*100)); fields.put("vnp_ResponseCode",success ? "00" : "24");
        fields.put("vnp_TransactionStatus",success ? "00" : "02");
        fields.put("vnp_PayDate", order.createdAt().plusSeconds(1).format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")));
        fields.put("vnp_TransactionNo","TEST-123");
        fields.put("vnp_SecureHash", VnPayConfig.hmacSHA512(config.secretKey(),VnPayService.canonicalQuery(fields)));
        return fields;
    }
    String settle(Map<String,String> fields) { return db.tx.execute(tx -> service.handlePaymentReturn(fields)).getStatus(); }
    String holdStatus() { return db.jdbc.queryForObject("SELECT status FROM booking_reservation",String.class); }
    @Test void suspendedScheduleRejectsCheckoutAndLateSuccessRequiresReconciliation() {
        var fields=callback(prepare(),true);
        db.jdbc.update("UPDATE tour_departure SET operational_status='SUSPENDED'");
        assertThrows(org.springframework.web.server.ResponseStatusException.class,this::prepare);
        assertEquals("reconciliation",settle(fields));assertEquals(0,db.count("invoice"));
        assertEquals(1,db.count("payment_reconciliation"));assertEquals(9,db.seats());
    }
    @Test void failedCheckoutWhileSuspendedDoesNotExpireAffectedBooking() {
        db.jdbc.update("UPDATE booking_reservation SET status='CHECKOUT',expires_at=?",LocalDateTime.now(clock).plusMinutes(15));
        var fields=callback(prepare(),false);db.jdbc.update("UPDATE tour_departure SET operational_status='SUSPENDED'");
        assertEquals("failed",settle(fields));assertEquals("CHECKOUT",holdStatus());assertEquals(9,db.seats());
        assertEquals("CONFIRMED",db.jdbc.queryForObject("SELECT status FROM booking",String.class));
    }
    @Test void paidHeldBookingDoesNotDeductSeatsTwiceAndEmailsOnce() {
        var fields = callback(prepare(),true); assertEquals("success",settle(fields)); assertEquals("success",settle(fields));
        assertEquals(9,db.seats()); assertEquals("PAID",holdStatus()); assertEquals(1,db.count("invoice")); assertEquals(1,db.count("mail_outbox"));
        assertTrue(db.jdbc.queryForObject("SELECT payload FROM mail_outbox",String.class).contains("3350000"));
    }
    @Test void heldLastSeatCanBePaidEvenWhenNoUnreservedSeatsRemain() {
        db.jdbc.update("UPDATE tour_departure SET available_seats=0");
        assertEquals("success",settle(callback(prepare(),true))); assertEquals(0,db.seats());
    }
    @Test void longHoldFailedPaymentKeepsSeatAndAllowsAnotherAttempt() {
        assertEquals("failed",settle(callback(prepare(),false))); assertEquals(9,db.seats()); assertEquals("HELD",holdStatus());
        assertEquals(0,db.count("mail_outbox")); assertNotNull(prepare()); assertEquals(2,db.count("vnpay_transaction"));
    }
    @Test void immediateFailedPaymentCancelsTemporaryBookingAndReturnsSeatOnce() {
        db.jdbc.update("UPDATE booking_reservation SET status='CHECKOUT',expires_at=?",LocalDateTime.now(clock).plusMinutes(15));
        var fields=callback(prepare(),false); settle(fields); settle(fields);
        assertEquals(10,db.seats()); assertEquals("EXPIRED",holdStatus()); assertEquals(1,db.count("mail_outbox"));
        assertEquals("EXPIRED",db.jdbc.queryForObject("SELECT status FROM booking",String.class));
    }
    @Test void paymentWindowNeverExtendsBookingDeadline() {
        db.jdbc.update("UPDATE booking_reservation SET expires_at=?",LocalDateTime.now(clock).plusMinutes(2));
        assertEquals(LocalDateTime.now(clock).plusMinutes(2),prepare().expiresAt());
    }
    @Test void expiredHoldCannotStartOrCompletePayment() {
        var order=prepare(); clock.set(LocalDateTime.of(2026,11,7,8,0));
        assertThrows(IllegalArgumentException.class,this::prepare);
        // A real sweep expires the booking together with its hold, so a later capture is reviewed manually.
        db.jdbc.update("UPDATE booking SET status='EXPIRED'");
        db.jdbc.update("UPDATE booking_reservation SET status='EXPIRED'");
        assertEquals("reconciliation",settle(callback(order,true))); assertEquals("reconciliation",settle(callback(order,true)));
        assertEquals(1,db.count("payment_reconciliation")); assertEquals(0,db.count("invoice")); assertEquals(0,db.count("mail_outbox"));
    }
    @Test void timelyCaptureSettlesWhileIpnArrivesAfterHoldExpiry() {
        var order=prepare(); clock.set(LocalDateTime.of(2026,11,7,8,0));
        // The provider says the customer paid inside the checkout window and the unpaid hold was
        // never released, so the payment must complete instead of being reconciled away.
        assertEquals("success",settle(callback(order,true)));
        assertEquals("PAID",holdStatus()); assertEquals(1,db.count("invoice"));
    }
    @Test void callbackAfterCancellationDoesNotRestoreBookingOrConsumeSeats() {
        var fields=callback(prepare(),true);
        db.tx.executeWithoutResult(tx -> {
            db.jdbc.queryForMap("SELECT id FROM tour_departure WHERE id=1 FOR UPDATE");
            db.jdbc.queryForMap("SELECT id FROM booking WHERE id=1 FOR UPDATE");
            db.jdbc.update("UPDATE booking_reservation SET status='CANCELLED'"); db.jdbc.update("UPDATE booking SET status='CANCELLED'");
            db.jdbc.update("UPDATE tour_departure SET available_seats=10");
        });
        assertEquals("reconciliation",settle(fields)); assertEquals(10,db.seats()); assertEquals("CANCELLED",holdStatus());
    }
    @Test void mailFailureRollsBackPaymentAndKeepsHold() {
        var fields=callback(prepare(),true);
        db.jdbc.execute("ALTER TABLE mail_outbox ADD CONSTRAINT reject_receipt CHECK(event_type='TEST_MAIL')");
        assertThrows(RuntimeException.class,()->settle(fields)); assertEquals("HELD",holdStatus()); assertEquals(0,db.count("payment"));
        assertEquals(0,db.count("invoice")); assertEquals(9,db.seats());
        assertEquals("PENDING",db.jdbc.queryForObject("SELECT status FROM vnpay_transaction",String.class));
    }
    @Test void concurrentCallbacksProduceOnlyOneReceipt() throws Exception {
        var fields=callback(prepare(),true);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->settle(fields)); var b=pool.submit(()->settle(fields));
            assertEquals("success",a.get(10,TimeUnit.SECONDS)); assertEquals("success",b.get(10,TimeUnit.SECONDS));
        }
        assertEquals(1,db.count("mail_outbox")); assertEquals(1,db.count("payment")); assertEquals(9,db.seats());
    }
    @Test void changedDepartureDoesNotSilentlyChargeAnotherTrip() {
        var fields=callback(prepare(),true); db.jdbc.update("UPDATE tour_departure SET departure_date=?",LocalDateTime.of(2026,11,21,6,0));
        assertEquals("reconciliation",settle(fields)); assertEquals(0,db.count("invoice"));
    }
    @Test void changedBookingOwnerCannotReceiveAnotherAccountsPayment() {
        var fields=callback(prepare(),true); db.jdbc.update("UPDATE booking SET account_id=8 WHERE id=1");
        assertEquals("reconciliation",settle(fields)); assertEquals(0,db.count("invoice")); assertEquals(9,db.seats());
    }
}
