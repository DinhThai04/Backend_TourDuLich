package com.example.booking_service.reservation;

import com.example.booking_service.dto.request.*;
import com.example.booking_service.config.BookingMailOutboxPublisher;
import com.example.booking_service.config.NotificationMailClient;
import com.example.booking_service.notification.MailEventType;
import com.example.booking_service.repository.httpclient.AuthInternalClient;
import com.example.booking_service.repository.httpclient.InvoiceInternalClient;
import com.example.booking_service.repository.httpclient.PricingInternalClient;
import com.example.booking_service.repository.httpclient.TourInternalClient;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Uses the Booking and Payment implementations with a shared test DB and in-process dependency adapters. */
@EnabledIfEnvironmentVariable(named="MAIL_CROSS_SERVICE_TEST",matches="true")
class CrossServiceLifecycleTest {
    LifecycleTestDatabase db;
    LifecycleTestDatabase.MutableClock clock;
    BookingLifecycleService lifecycle;
    ReservationExpiryService expiry;
    Object orders;
    URLClassLoader paymentClasses;
    int id;
    JsonMapper mapper;
    BookingSettlementListener settlementListener;
    @BeforeEach void setup() throws Exception {
        db=new LifecycleTestDatabase(); clock=new LifecycleTestDatabase.MutableClock(LocalDateTime.of(2026,11,1,10,0));
        mapper=JsonMapper.builder().findAndAddModules().build();
        var authClient = mock(AuthInternalClient.class);
        var tourClient = com.example.booking_service.TestInternalClients.tour(db.jdbc);
        var payment = com.example.booking_service.TestInternalClients.payment(db.jdbc, clock);
        var invoice = com.example.booking_service.TestInternalClients.invoice(db.jdbc);
        var pricing = mock(PricingInternalClient.class);
        when(authClient.getUserById(anyLong())).thenReturn(Map.of("email", "tester@example.com", "username", "tester"));
        when(authClient.getUserStatus(anyLong())).thenReturn(Map.of("status", "ACTIVE"));
        when(pricing.getPriceById(1L)).thenReturn(Map.of("id", 1, "adultPrice", 3350000,
                "childPrice", 2000000, "toddlerPrice", 0, "infantPrice", 0, "singleSupplementPrice", 500));
        var publisher = new BookingMailOutboxPublisher(db.jdbc, mock(NotificationMailClient.class), "1970-01-01T00:00:00");
        var events = new BookingMailEvents(db.jdbc, publisher, clock, authClient, tourClient);
        expiry = new ReservationExpiryService(db.jdbc, db.manager, events, clock, tourClient, payment, invoice);
        var access = mock(BookingAccess.class);
        when(access.accountId()).thenReturn(7);
        lifecycle = new BookingLifecycleService(db.jdbc, db.manager, new ReservationPolicy(), expiry,
                events, access, clock, authClient, invoice, pricing, tourClient);
        settlementListener = new BookingSettlementListener(mapper, new BookingSettlementService(db.jdbc, tourClient, clock));
        id = Integer.parseInt(lifecycle.create(BookingRequest.builder().userId("7").tourDepartureId("1")
                .listOfCustomers(List.of(CustomerRequest.builder().fullName("Danh").birthdate("2000-01-01").gender("Male").address("12 Đường Lê Lợi, Phường Ba Đình, Thành phố Hà Nội").build()))
                .build()).getId());

        db.jdbc.execute("""
                CREATE TABLE payment_outbox (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,event_id VARCHAR(36) NOT NULL,event_type VARCHAR(120) NOT NULL,
                    aggregate_type VARCHAR(80) NOT NULL,aggregate_id VARCHAR(80) NOT NULL,aggregate_version INT NOT NULL,
                    correlation_id VARCHAR(64),causation_id VARCHAR(64),payload CLOB NOT NULL,status VARCHAR(20) NOT NULL,
                    attempts INT NOT NULL,available_at DATETIME(6) NOT NULL,created_at DATETIME(6) NOT NULL
                )
                """);
        var urls = new java.net.URL[]{
                Path.of("../payment_service/target/classes").toUri().toURL(),
                Path.of("../payment_service/target/test-classes").toUri().toURL()};
        paymentClasses = new URLClassLoader(urls, getClass().getClassLoader());
        orders = paymentClasses.loadClass("com.example.payment_service.TestServiceClients")
                .getMethod("vnpay", org.springframework.jdbc.core.JdbcTemplate.class, Clock.class,
                        com.fasterxml.jackson.databind.ObjectMapper.class)
                .invoke(null, db.jdbc, clock, mapper);
    }
    @AfterEach void close() throws Exception { if(paymentClasses!=null) paymentClasses.close(); }
    Object call(String method,Class<?>[] types,Object... arguments) {
        try{return orders.getClass().getMethod(method,types).invoke(orders,arguments);}
        catch(java.lang.reflect.InvocationTargetException e){throw (RuntimeException)e.getCause();}
        catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
    }
    Map<String,String> prepare() {
        Object order=db.tx.execute(tx->call("prepare",new Class<?>[]{int.class,int.class,String.class},id,7,"3350000"));
        try {
            String ref=(String)order.getClass().getMethod("txnRef").invoke(order);
            var fields=new HashMap<String,String>(); fields.put("vnp_TxnRef",ref);fields.put("vnp_Amount","335000000");
            fields.put("vnp_ResponseCode","00");fields.put("vnp_TransactionStatus","00");fields.put("vnp_TransactionNo","CROSS-SERVICE-TEST");
            fields.put("vnp_PayDate",LocalDateTime.now(clock).format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")));
            return fields;
        }catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
    }
    private String settle(Map<String, String> fields) {
        return db.tx.execute(tx -> {
            Object result = call("settle", new Class<?>[]{Map.class}, fields);
            try {
                return (String) result.getClass().getMethod("status").invoke(result);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
        });
    }

    private void deliverSettlement() {
        String payload = db.jdbc.queryForObject(
                "SELECT payload FROM payment_outbox WHERE event_type='payment.settled.v1'", String.class);
        assertNotNull(payload);
        db.tx.executeWithoutResult(tx -> {
            try {
                settlementListener.settled(payload.getBytes(StandardCharsets.UTF_8));
            } catch (java.io.IOException invalid) {
                throw new IllegalStateException(invalid);
            }
        });
    }

    @Test void actualBookingAndPaymentProduceCorrectSeatsAndTwoEvents() {
        var fields = prepare();
        assertEquals("success", settle(fields));
        assertEquals("duplicate", settle(fields));
        assertEquals("HELD", lifecycle.get(id).getReservationStatus());
        assertEquals(2, db.count("payment_outbox"));
        deliverSettlement();
        deliverSettlement();
        assertEquals("PAID", lifecycle.get(id).getReservationStatus());
        assertEquals(9, db.seats());
        clock.set(LocalDateTime.of(2026,11,8,10,0));
        expiry.sweep();
        assertEquals(9, db.seats());
    }

    @Test void actualCancelAndSettlementRacePreservesCapacityAndFinancialHistory() throws Exception {
        var fields = prepare();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var paid = pool.submit(() -> settle(fields));
            var cancelled = pool.submit(() -> {
                try {
                    lifecycle.cancel(id);
                } catch (org.springframework.web.server.ResponseStatusException blocked) {
                    assertEquals(409, blocked.getStatusCode().value());
                }
            });
            String result = paid.get(10, TimeUnit.SECONDS);
            cancelled.get(10, TimeUnit.SECONDS);
            if ("success".equals(result)) {
                deliverSettlement();
                assertEquals("PAID", lifecycle.get(id).getReservationStatus());
                assertEquals(9, db.seats());
                assertEquals(1, db.count("payment"));
            } else {
                assertEquals("reconciliation", result);
                assertEquals(10, db.seats());
                assertEquals(0, db.count("payment"));
                assertEquals(1, db.count("payment_reconciliation"));
            }
        }
        assertEquals(1, db.count("booking"));
    }

    @Test void actualExpiryAndLateCallbackNeverRestoreExpiredBooking() {
        clock.set(LocalDateTime.of(2026,11,7,7,55));
        var fields = prepare();
        clock.set(LocalDateTime.of(2026,11,7,8,5));
        expiry.sweep();
        assertEquals("reconciliation", settle(fields));
        assertEquals(10, db.seats());
        assertEquals("EXPIRED", lifecycle.get(id).getReservationStatus());
        assertEquals(1, db.count("payment_reconciliation"));
    }
}
