package com.example.booking_service.reservation;

import com.example.booking_service.dto.request.*;
import com.example.booking_service.notification.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Both real service implementations share the isolated test DB; payment_service must be compiled first. */
@EnabledIfEnvironmentVariable(named="MAIL_CROSS_SERVICE_TEST",matches="true")
class CrossServiceLifecycleTest {
    LifecycleTestDatabase db;
    LifecycleTestDatabase.MutableClock clock;
    BookingLifecycleService lifecycle;
    ReservationExpiryService expiry;
    Object orders;
    URLClassLoader paymentClasses;
    int id;
    @BeforeEach void setup() throws Exception {
        db=new LifecycleTestDatabase(); clock=new LifecycleTestDatabase.MutableClock(LocalDateTime.of(2026,11,1,10,0));
        var mapper=JsonMapper.builder().findAndAddModules().build();
        var events=new BookingMailEvents(db.jdbc,new MailOutboxService(new MailOutboxRepository(db.jdbc,db.manager),mapper,clock),clock);
        expiry=new ReservationExpiryService(db.jdbc,db.manager,events,clock);
        var access=mock(BookingAccess.class); when(access.accountId()).thenReturn(7);
        lifecycle=new BookingLifecycleService(db.jdbc,db.manager,new ReservationPolicy(),expiry,events,access,clock);
        id=Integer.parseInt(lifecycle.create(BookingRequest.builder().userId("7").tourDepartureId("1").listOfCustomers(List.of(
            CustomerRequest.builder().fullName("Danh").birthdate("2000-01-01").gender("Male").build())).build()).getId());
        paymentClasses=new URLClassLoader(new java.net.URL[]{Path.of("../payment_service/target/classes").toUri().toURL()},getClass().getClassLoader());
        var writerClass=paymentClasses.loadClass("com.example.payment_service.notification.PaymentMailOutboxWriter");
        var writer=writerClass.getConstructor(org.springframework.jdbc.core.JdbcTemplate.class,com.fasterxml.jackson.databind.ObjectMapper.class).newInstance(db.jdbc,mapper);
        orders=paymentClasses.loadClass("com.example.payment_service.service.VnPayOrderService")
            .getConstructor(org.springframework.jdbc.core.JdbcTemplate.class,writerClass,Clock.class).newInstance(db.jdbc,writer,clock);
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
    String settle(Map<String,String> fields){return db.tx.execute(tx->(String)call("settle",new Class<?>[]{Map.class},fields));}
    @Test void actualBookingAndPaymentProduceCorrectSeatsAndTwoEvents() {
        var fields=prepare();assertEquals("success",settle(fields));assertEquals("duplicate",settle(fields));
        assertEquals(9,db.seats());assertEquals("PAID",lifecycle.get(id).getReservationStatus());assertEquals(2,db.count("mail_outbox"));
        clock.set(LocalDateTime.of(2026,11,8,10,0));expiry.sweep();assertEquals(9,db.seats());
    }
    @Test void actualCancelAndSettlementRacePreservesCapacityAndFinancialHistory() throws Exception {
        var fields=prepare();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var paid=pool.submit(()->settle(fields));
            var cancelled=pool.submit(()->{try{lifecycle.cancel(id);}catch(org.springframework.web.server.ResponseStatusException paidFirst){assertEquals(409,paidFirst.getStatusCode().value());}});
            String result=paid.get(10,TimeUnit.SECONDS);cancelled.get(10,TimeUnit.SECONDS);
            if("success".equals(result)){assertEquals(9,db.seats());assertEquals(1,db.count("payment"));}
            else{assertEquals("reconciliation",result);assertEquals(10,db.seats());assertEquals(0,db.count("payment"));assertEquals(1,db.count("payment_reconciliation"));}
        }
        assertEquals(1,db.count("booking"));assertEquals(2,db.count("mail_outbox"));
    }
    @Test void actualExpiryAndLateCallbackNeverRestoreExpiredBooking() {
        clock.set(LocalDateTime.of(2026,11,7,7,55));var fields=prepare();
        clock.set(LocalDateTime.of(2026,11,7,8,0));expiry.sweep();
        assertEquals("reconciliation",settle(fields));assertEquals(10,db.seats());assertEquals("EXPIRED",lifecycle.get(id).getReservationStatus());
    }
}
