package com.example.tour_service;

import com.example.tour_service.entity.*;
import com.example.tour_service.enums.LocationType;
import com.example.tour_service.service.*;
import com.example.tour_service.repository.httpClient.PricingClient;
import com.example.tour_service.dto.request.TourDepartureRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import java.math.BigDecimal;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.lang.reflect.InvocationTargetException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.sql.init.mode=never","spring.jpa.show-sql=false"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
@Import({TourDepartureService.class,DepartureCapacityReader.class})
class DepartureCapacityTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        String external=System.getenv("CAPACITY_TEST_JDBC_URL");
        if(external!=null && !external.matches("jdbc:mysql://127\\.0\\.0\\.1:3306/sgu_capacity_test_[0-9]+(?:\\?.*)?"))
            throw new IllegalArgumentException("Only an isolated sgu_capacity_test_<digits> schema is allowed; never DBtour");
        properties.add("spring.datasource.url",()->external==null?"jdbc:h2:mem:capacity;MODE=MySQL;DB_CLOSE_DELAY=-1":external);
        properties.add("spring.datasource.username",()->external==null?"sa":"root");
        properties.add("spring.datasource.password",()->external==null?"":System.getenv("CAPACITY_TEST_DB_PASSWORD"));
        properties.add("spring.datasource.driver-class-name",()->external==null?"org.h2.Driver":"com.mysql.cj.jdbc.Driver");
        properties.add("spring.jpa.properties.hibernate.dialect",()->external==null?"org.hibernate.dialect.H2Dialect":"org.hibernate.dialect.MySQLDialect");
    }
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired TourDepartureService service;
    @MockBean PricingClient pricing;
    final LocalDateTime start=LocalDateTime.of(2090,1,20,7,0);
    int id,tourId;
    TransactionTemplate tx;
    @BeforeEach void seed() {
        tx=new TransactionTemplate(manager);
        for(String ddl:List.of(
          "CREATE TABLE IF NOT EXISTS booking_change(id BIGINT PRIMARY KEY,booking_id INT,status VARCHAR(30),refund_amount DECIMAL(19,0),refund_reference VARCHAR(255),source_departure_id INT,target_departure_id INT)",
          "CREATE TABLE IF NOT EXISTS departure_change(id BIGINT PRIMARY KEY,source_departure_id INT,target_departure_id INT)",
          "CREATE TABLE IF NOT EXISTS users(id INT PRIMARY KEY,username VARCHAR(50),email VARCHAR(254))",
          "CREATE TABLE IF NOT EXISTS booking(id INT AUTO_INCREMENT PRIMARY KEY,account_id INT,tour_departure_id INT,status VARCHAR(20),created_at DATETIME(6))",
          "CREATE TABLE IF NOT EXISTS customer(id INT AUTO_INCREMENT PRIMARY KEY,booking_id INT,full_name VARCHAR(255),birthdate DATE,gender BOOLEAN,address VARCHAR(255),booking_type VARCHAR(20),status VARCHAR(20))",
          "CREATE TABLE IF NOT EXISTS booking_reservation(booking_id INT PRIMARY KEY,departure_id INT,departure_at DATETIME(6),seats INT,status VARCHAR(20),expires_at DATETIME(6),reminder_one_at DATETIME(6),reminder_two_at DATETIME(6),created_at DATETIME(6),updated_at DATETIME(6),cancel_reason VARCHAR(40))",
          "CREATE TABLE IF NOT EXISTS invoice(id INT AUTO_INCREMENT PRIMARY KEY,booking_id INT,account_id INT,status VARCHAR(20),day_of_pay DATETIME(6),pay_method_id INT,total_booking_tour_expense DECIMAL(19,0))",
          "CREATE TABLE IF NOT EXISTS payment(id INT AUTO_INCREMENT PRIMARY KEY,booking_id INT,account_id INT,status VARCHAR(20),day_of_pay DATETIME(6),pay_method_id INT,total_booking_tour_expense DECIMAL(19,0))",
          "CREATE TABLE IF NOT EXISTS vnpay_transaction(txn_ref VARCHAR(32) PRIMARY KEY,booking_id INT,account_id INT,amount_vnd BIGINT,status VARCHAR(20),created_at DATETIME(6),expires_at DATETIME(6),transaction_no VARCHAR(255))",
          "CREATE TABLE IF NOT EXISTS payment_reconciliation(txn_ref VARCHAR(32) PRIMARY KEY,booking_id INT,reason VARCHAR(100),status VARCHAR(20) DEFAULT 'OPEN',provider_paid_at DATETIME(6),amount_vnd BIGINT,created_at DATETIME(6))")) jdbc.execute(ddl);
        for(String table:List.of("booking_change","departure_change","payment_reconciliation","vnpay_transaction","invoice","payment","booking_reservation","customer","booking","users","tour_departure","tour","location","vehicles","prices"))jdbc.update("DELETE FROM "+table);
        jdbc.update("INSERT INTO users VALUES(7,'Test','capacity@example.test')");
        tx.executeWithoutResult(status->{
            var location=Location.builder().city("Fixture").img("fixture.png").type(LocationType.DEPARTURE).build();em.persist(location);
            var vehicle=Vehicle.builder().id("CAP-BUS").vehicleType("Bus").build();em.persist(vehicle);
            var tour=Tour.builder().tourProgram("CAP").tourTitle("Capacity fixture").description("Test only").duration(2).basePrice(BigDecimal.ONE).tourPriceId(1L).departureLocation(location).destinationLocation(location).vehicle(vehicle).build();em.persist(tour);tourId=tour.getId();
            var departure=TourDeparture.builder().tour(tour).tourCode("CAP-TEST").departureDate(start).returnDate(start.plusDays(2)).availableSeats((short)10).capacity(10).build();em.persist(departure);em.flush();id=departure.getId();
        });
    }
    TourDepartureRequest edit(int capacity,long version) { return TourDepartureRequest.builder().capacity(capacity).expectedVersion(version).build(); }
    void conflict(Runnable action) { assertEquals(409,assertThrows(ResponseStatusException.class,action::run).getStatusCode().value()); }
    int seats(){return jdbc.queryForObject("SELECT available_seats FROM tour_departure WHERE id=?",Integer.class,id);}
    int legacy(int people,boolean paid) {
        jdbc.update("INSERT INTO booking(account_id,tour_departure_id,status,created_at) VALUES(7,?,'CONFIRMED',?)",id,start.minusDays(20));
        int booking=jdbc.queryForObject("SELECT MAX(id) FROM booking",Integer.class);
        for(int i=0;i<people;i++)jdbc.update("INSERT INTO customer(booking_id,booking_type) VALUES(?,'ADULT')",booking);
        if(paid){jdbc.update("INSERT INTO invoice(booking_id,status) VALUES(?,'PAID')",booking);jdbc.update("INSERT INTO payment(booking_id,status) VALUES(?,'PAID')",booking);}
        return booking;
    }
    @Test void updatesEmptyCapacityAndScheduleButRejectsStaleFormAndAbsoluteRemaining() {
        var request=edit(20,0);request.setDepartureDate(start.plusDays(1));request.setReturnDate(start.plusDays(3));
        conflict(()->service.update(id,request));
        assertEquals(20,service.update(id,edit(20,0)).getAvailableSeats().intValue());assertEquals(1,service.capacity(id).version());
        conflict(()->service.update(id,edit(30,0)));
        var old=edit(20,1);old.setAvailableSeats((short)30);assertThrows(IllegalArgumentException.class,()->service.update(id,old));
        var invalid=edit(1,1);invalid.setReturnDate(start.minusDays(1));conflict(()->service.update(id,invalid));
    }
    @Test void cannotDeleteTourAfterAChangeEvenWhenNoBookingRemainsOnSource() {
        assertDoesNotThrow(()->service.assertTourHasNoHistory(tourId));
        jdbc.update("INSERT INTO departure_change(id,source_departure_id,target_departure_id) VALUES(1,?,?)",id,id);
        conflict(()->service.assertTourHasNoHistory(tourId));
        jdbc.update("DELETE FROM departure_change");legacy(1,true);
        conflict(()->service.assertTourHasNoHistory(tourId));
    }
    @Test void legacyPaidIsCountedOnceAndUnknownTotalRequiresExplicitConfirmation() {
        legacy(2,true);jdbc.update("UPDATE tour_departure SET capacity=NULL,available_seats=8 WHERE id=?",id);
        var state=service.capacity(id);assertEquals(2,state.paidSeats());assertFalse(state.scheduleEditable());assertTrue(state.capacityReviewRequired());
        conflict(()->service.update(id,edit(12,state.version())));
        var confirmed=edit(12,state.version());confirmed.setConfirmCapacity(true);
        assertEquals(10,service.update(id,confirmed).getAvailableSeats().intValue());
        var changedDate=edit(12,service.capacity(id).version());changedDate.setDepartureDate(start.plusDays(1));conflict(()->service.update(id,changedDate));
    }
    @Test void heldSeatsCannotBeRemovedByReducingCapacityAndCancelledHistoryStillLocksDates() {
        int booking=legacy(3,false);
        jdbc.update("INSERT INTO booking_reservation(booking_id,departure_id,departure_at,seats,status,expires_at) VALUES(?,?,?,3,'HELD',?)",booking,id,start,start.minusDays(1));
        jdbc.update("UPDATE tour_departure SET available_seats=7 WHERE id=?",id);
        assertEquals(3,service.capacity(id).heldSeats());conflict(()->service.update(id,edit(2,0)));
        assertEquals(0,service.update(id,edit(3,0)).getAvailableSeats().intValue());
        jdbc.update("UPDATE booking_reservation SET status='CANCELLED' WHERE booking_id=?",booking);
        jdbc.update("UPDATE booking SET status='CANCELLED' WHERE id=?",booking);
        jdbc.update("UPDATE tour_departure SET available_seats=capacity WHERE id=?",id);
        var request=edit(3,service.capacity(id).version());request.setReturnDate(start.plusDays(3));conflict(()->service.update(id,request));
    }
    @Test void confirmedRepairFixesANonNullCapacityMismatchWithoutHidingOtherIssues() {
        legacy(1,false);
        var state=service.capacity(id);
        assertTrue(state.capacityReviewRequired());
        assertTrue(state.blockingIssues().isEmpty());
        assertTrue(state.issues().stream().anyMatch(issue -> issue.contains("Tổng sức chứa không khớp")));
        conflict(()->service.update(id,edit(12,0)));
        var confirmed=edit(12,state.version());confirmed.setConfirmCapacity(true);
        assertEquals(11,service.update(id,confirmed).getAvailableSeats().intValue());
    }
    @Test void unresolvedLegacyCheckoutBlocksCapacityEvenAfterItsLinkExpires() {
        int booking=legacy(1,false);
        // A legacy CONFIRMED booking already used one of the ten seats; the row must stay consistent.
        jdbc.update("UPDATE tour_departure SET available_seats=9 WHERE id=?",id);
        jdbc.update("INSERT INTO vnpay_transaction(txn_ref,booking_id,account_id,amount_vnd,status,created_at,expires_at) VALUES('pending',?,7,3350000,'PENDING',?,?)",booking,start.minusDays(20),LocalDateTime.of(2000,1,1,0,0));
        assertFalse(service.capacity(id).issues().isEmpty());conflict(()->service.update(id,edit(10,0)));
        jdbc.update("UPDATE vnpay_transaction SET status='FAILED' WHERE txn_ref='pending'");
        assertEquals(11,service.update(id,edit(12,0)).getAvailableSeats().intValue());
    }
    @Test void conflictingOrOverdueStateIsReportedWithoutRepair() {
        int booking=legacy(1,false);
        jdbc.update("INSERT INTO booking_reservation(booking_id,departure_id,departure_at,seats,status,expires_at) VALUES(?,?,?,1,'HELD',?)",booking,id,start,LocalDateTime.of(2000,1,1,0,0));
        jdbc.update("UPDATE tour_departure SET available_seats=9 WHERE id=?",id);
        assertEquals(1,service.capacity(id).overdueHolds());conflict(()->service.update(id,edit(20,0)));assertEquals(9,seats());
        jdbc.update("UPDATE booking_reservation SET status='PAID' WHERE booking_id=?",booking);
        assertFalse(service.capacity(id).issues().isEmpty());conflict(()->service.update(id,edit(20,0)));
    }
    @Test void onlyOneAdministratorCanSaveTheSameVersion() throws Exception {
        var gate=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var futures=new ArrayList<Future<Boolean>>();
            for(int total:List.of(11,12))futures.add(pool.submit(()->{gate.await();try{service.update(id,edit(total,0));return true;}catch(ResponseStatusException stale){assertEquals(409,stale.getStatusCode().value());return false;}}));
            int accepted=0;for(var future:futures)if(future.get(20,TimeUnit.SECONDS))accepted++;
            assertEquals(1,accepted);assertEquals(1,service.capacity(id).version());
        }
    }
    @Test void createValidatesCapacityAndFutureDates() {
        var request=TourDepartureRequest.builder().tourId(tourId).departureDate(start.plusDays(4)).returnDate(start.plusDays(5)).capacity(0).build();
        assertThrows(IllegalArgumentException.class,()->service.create(request));
        request.setCapacity(32768);assertThrows(IllegalArgumentException.class,()->service.create(request));
        request.setCapacity(1);request.setDepartureDate(LocalDateTime.of(2000,1,1,0,0));assertThrows(IllegalArgumentException.class,()->service.create(request));
    }

    // Cross-module fixture loads real compiled services, not a reimplementation of their seat SQL.
    class LiveServices implements AutoCloseable {
        final URLClassLoader loader;
        final Object lifecycle,expiry,payment;
        final Class<?> requestType;
        final MutableClock clock=new MutableClock();
        LiveServices() throws Exception {
            var bookingPath=Path.of("../booking_service/target/classes");var paymentPath=Path.of("../payment_service/target/classes");
            assertTrue(Files.exists(bookingPath.resolve("com/example/booking_service/reservation/BookingLifecycleService.class")),"Build booking_service and payment_service first");
            loader=new URLClassLoader(new java.net.URL[]{bookingPath.toUri().toURL(),paymentPath.toUri().toURL()},getClass().getClassLoader());
            var eventsType=loader.loadClass("com.example.booking_service.reservation.BookingMailEvents");var events=mock(eventsType);
            var policyType=loader.loadClass("com.example.booking_service.reservation.ReservationPolicy");var accessType=loader.loadClass("com.example.booking_service.reservation.BookingAccess");
            var expiryType=loader.loadClass("com.example.booking_service.reservation.ReservationExpiryService");
            expiry=expiryType.getConstructors()[0].newInstance(jdbc,manager,events,clock);
            lifecycle=loader.loadClass("com.example.booking_service.reservation.BookingLifecycleService").getConstructors()[0].newInstance(jdbc,manager,policyType.getConstructor().newInstance(),expiry,events,accessType.getConstructor().newInstance(),clock);
            var writerType=loader.loadClass("com.example.payment_service.notification.PaymentMailOutboxWriter");
            payment=loader.loadClass("com.example.payment_service.service.VnPayOrderService").getConstructors()[0].newInstance(jdbc,mock(writerType),clock);
            requestType=loader.loadClass("com.example.booking_service.dto.request.BookingRequest");
            try(var connection=jdbc.getDataSource().getConnection();var rows=connection.createStatement().executeQuery("SELECT * FROM prices WHERE 1=0")) {
                var columns=new HashSet<String>();for(int i=1;i<=rows.getMetaData().getColumnCount();i++)columns.add(rows.getMetaData().getColumnName(i).toLowerCase());
                for(String col:List.of("child_price","toddler_price","infant_price","single_supplement_price"))if(!columns.contains(col))jdbc.execute("ALTER TABLE prices ADD "+col+" DECIMAL(15,2)");
            }
            jdbc.update("INSERT INTO prices(id,adult_price,child_price,toddler_price,infant_price,single_supplement_price) VALUES(1,3350000,2000000,0,0,0)");
        }
        int book() {
            try {
                SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(Jwt.withTokenValue("fixture").header("alg","HS512").subject("7").build(),List.of()));
                Object request=new ObjectMapper().readValue("{\"userId\":\"7\",\"tourDepartureId\":\""+id+"\",\"listOfCustomers\":[{\"fullName\":\"Test\",\"birthdate\":\"2000-01-01\",\"gender\":\"Male\",\"address\":\"Test\"}]}",requestType);
                Object result=invoke(lifecycle,"create",new Class<?>[]{requestType},request);
                return Integer.parseInt(String.valueOf(result.getClass().getMethod("getId").invoke(result)));
            } catch(RuntimeException error){throw error;}catch(Exception error){throw new IllegalStateException(error);}finally{SecurityContextHolder.clearContext();}
        }
        Map<String,String> checkout(int booking) {
            Object order=tx.execute(status->invoke(payment,"prepare",new Class<?>[]{int.class,int.class,String.class},booking,7,"3350000"));
            try {
                String ref=String.valueOf(order.getClass().getMethod("txnRef").invoke(order));
                return Map.of("vnp_TxnRef",ref,"vnp_Amount","335000000","vnp_ResponseCode","00","vnp_TransactionStatus","00","vnp_TransactionNo","TEST","vnp_PayDate",LocalDateTime.now(clock).plusSeconds(1).format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss")));
            }catch(Exception error){throw new IllegalStateException(error);}
        }
        String settle(Map<String,String> fields){return tx.execute(status->(String)invoke(payment,"settle",new Class<?>[]{Map.class},fields));}
        void expire(){invoke(expiry,"sweep",new Class<?>[]{});}
        public void close()throws Exception{loader.close();}
    }
    static Object invoke(Object target,String name,Class<?>[] types,Object... args) {
        try{return target.getClass().getMethod(name,types).invoke(target,args);}
        catch(InvocationTargetException error){if(error.getCause() instanceof RuntimeException failure)throw failure;throw new IllegalStateException(error.getCause());}
        catch(ReflectiveOperationException error){throw new IllegalStateException(error);}
    }
    static class MutableClock extends Clock {
        volatile LocalDateTime time=LocalDateTime.of(2090,1,1,10,0);
        public ZoneId getZone(){return ZoneId.of("Asia/Ho_Chi_Minh");}public Clock withZone(ZoneId zone){return Clock.fixed(instant(),zone);}public Instant instant(){return time.atZone(getZone()).toInstant();}
    }
    @Test @EnabledIfEnvironmentVariable(named="CAPACITY_CROSS_SERVICE_TEST",matches="true")
    void actualBookingServiceSerializesTheLastSeatAndInvalidatesAdminForm()throws Exception {
        jdbc.update("UPDATE tour_departure SET capacity=1,available_seats=1 WHERE id=?",id);
        try(var live=new LiveServices();var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CyclicBarrier(2);var futures=new ArrayList<Future<Boolean>>();
            for(int i=0;i<2;i++)futures.add(pool.submit(()->{gate.await();try{live.book();return true;}catch(ResponseStatusException full){assertEquals(409,full.getStatusCode().value());return false;}}));
            int accepted=0;for(var future:futures)if(future.get(20,TimeUnit.SECONDS))accepted++;
            assertEquals(1,accepted);assertEquals(0,seats());conflict(()->service.update(id,edit(2,0)));
        }
    }
    @Test @EnabledIfEnvironmentVariable(named="CAPACITY_CROSS_SERVICE_TEST",matches="true")
    void reducingCapacityAndBookingTheLastSeatCannotBothSucceed()throws Exception {
        jdbc.update("UPDATE tour_departure SET capacity=1,available_seats=1 WHERE id=?",id);
        try(var live=new LiveServices();var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CyclicBarrier(2);
            var book=pool.submit(()->{gate.await();try{live.book();return true;}catch(ResponseStatusException conflict){assertEquals(409,conflict.getStatusCode().value());return false;}});
            var resize=pool.submit(()->{gate.await();try{service.update(id,edit(0,0));return true;}catch(ResponseStatusException conflict){assertEquals(409,conflict.getStatusCode().value());return false;}catch(IllegalArgumentException invalid){return false;}});
            assertNotEquals(book.get(20,TimeUnit.SECONDS),resize.get(20,TimeUnit.SECONDS));
            var state=service.capacity(id);assertEquals(state.capacity().intValue(),state.availableSeats()+state.paidSeats()+state.heldSeats());assertEquals(0,state.availableSeats());
        }
    }
    @Test @EnabledIfEnvironmentVariable(named="CAPACITY_CROSS_SERVICE_TEST",matches="true")
    void actualPaymentChangesVersionWithoutTakingSeatsTwiceAndExpiryCannotReleasePaidSeats()throws Exception {
        try(var live=new LiveServices()) {
            int booking=live.book();var fields=live.checkout(booking);var before=service.capacity(id);
            assertEquals("success",live.settle(fields));assertEquals("duplicate",live.settle(fields));assertEquals(9,seats());
            conflict(()->service.update(id,edit(20,before.version())));
            live.clock.time=LocalDateTime.of(2090,1,8,10,0);live.expire();assertEquals(9,seats());assertEquals(1,service.capacity(id).paidSeats());
        }
    }
    @Test @EnabledIfEnvironmentVariable(named="CAPACITY_CROSS_SERVICE_TEST",matches="true")
    void expiredCallbackRaceNeverReturnsSeatsTwiceOrFulfilsExpiredBooking()throws Exception {
        try(var live=new LiveServices();var pool=Executors.newFixedThreadPool(2)) {
            int booking=live.book();var fields=live.checkout(booking);live.clock.time=LocalDateTime.of(2090,1,8,10,0);
            var gate=new CyclicBarrier(2);
            var expiry=pool.submit(()->{gate.await();live.expire();return true;});
            var callback=pool.submit(()->{gate.await();return live.settle(fields);});
            expiry.get(20,TimeUnit.SECONDS);assertEquals("reconciliation",callback.get(20,TimeUnit.SECONDS));
            live.expire();live.settle(fields);assertTrue(seats()==9 || seats()==10);assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM payment_reconciliation",Integer.class));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM payment",Integer.class));assertFalse(service.capacity(id).issues().isEmpty());
        }
    }
}
