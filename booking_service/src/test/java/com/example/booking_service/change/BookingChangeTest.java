package com.example.booking_service.change;

import com.example.booking_service.reservation.*;
import com.example.booking_service.notification.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.booking_service.change.ChangeLedger.*;

class BookingChangeTest {
    LifecycleTestDatabase db;
    LifecycleTestDatabase.MutableClock clock;
    BookingChangeService service;
    BookingLifecycleService lifecycle;
    ReservationExpiryService expiry;
    MailEligibility eligibility;
    final LocalDateTime start=LocalDateTime.of(2026,11,20,6,0);
    void login(int id,String... permissions){
        var jwt=Jwt.withTokenValue("test").header("alg","none").subject(String.valueOf(id)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt,Arrays.stream(permissions).map(SimpleGrantedAuthority::new).toList()));
    }
    void admin(){login(8,"PERM:CHANGES_READ","PERM:CHANGES_REVIEW","PERM:SHOP_CHANGE_CREATE","PERM:REFUNDS_RECORD");}
    @BeforeEach void setup() throws Exception {
        db=new LifecycleTestDatabase();clock=new LifecycleTestDatabase.MutableClock(LocalDateTime.of(2026,11,1,10,0));
        db.jdbc.execute("ALTER TABLE tour_departure ADD COLUMN return_date DATETIME(6)");
        db.jdbc.execute("ALTER TABLE tour_departure ADD COLUMN tour_code VARCHAR(100)");
        db.jdbc.update("UPDATE tour_departure SET capacity=10,return_date=?,tour_code='SOURCE'",start.plusDays(2));
        db.jdbc.update("INSERT INTO tour_departure(id,tour_id,departure_date,return_date,available_seats,capacity,tour_code) VALUES(2,1,?,?,10,10,'TARGET')",start.plusDays(10),start.plusDays(12));
        var outbox=new MailOutboxService(new MailOutboxRepository(db.jdbc,db.manager),JsonMapper.builder().findAndAddModules().build(),clock);
        var access=new BookingAccess();var events=new BookingMailEvents(db.jdbc,outbox,clock);
        expiry=new ReservationExpiryService(db.jdbc,db.manager,events,clock);
        lifecycle=new BookingLifecycleService(db.jdbc,db.manager,new ReservationPolicy(),expiry,events,access,clock);
        service=new BookingChangeService(db.jdbc,new ChangeLedger(db.jdbc),access,new ReservationPolicy(),new ChangeMailEvents(db.jdbc,outbox,clock),clock,db.manager);
        eligibility=new MailEligibility(db.jdbc,clock);login(7);
    }
    @AfterEach void cleanup(){SecurityContextHolder.clearContext();}
    int booking(boolean paid){
        int id=db.jdbc.queryForObject("SELECT COALESCE(MAX(id),0)+1 FROM booking",Integer.class);
        db.jdbc.update("INSERT INTO booking (id,account_id,tour_departure_id,status,created_at) VALUES (?,7,1,'CONFIRMED',?)",id,LocalDateTime.now(clock));
        db.jdbc.update("INSERT INTO customer(booking_id,booking_type) VALUES(?,'ADULT')",id);
        db.jdbc.update("UPDATE tour_departure SET available_seats=available_seats-1 WHERE id=1");
        db.jdbc.update("INSERT INTO booking_reservation(booking_id,departure_id,departure_at,seats,status,expires_at,created_at,updated_at) VALUES(?,1,?,1,?,?,?,?)",id,start,paid?"PAID":"HELD",LocalDateTime.now(clock).plusDays(6),LocalDateTime.now(clock),LocalDateTime.now(clock));
        if(paid)for(String table:List.of("invoice","payment"))db.jdbc.update("INSERT INTO "+table+"(booking_id,account_id,status,total_booking_tour_expense,day_of_pay) VALUES(?,7,'PAID',3350000,?)",id,LocalDateTime.now(clock));
        return id;
    }
    int pendingBooking(int departure){
        int id=db.jdbc.queryForObject("SELECT COALESCE(MAX(id),0)+1 FROM booking",Integer.class);
        var at=db.jdbc.queryForObject("SELECT departure_date FROM tour_departure WHERE id=?",LocalDateTime.class,departure);
        db.jdbc.update("INSERT INTO booking (id,account_id,tour_departure_id,status,created_at) VALUES (?,7,?,'PENDING_PAYMENT',?)",id,departure,LocalDateTime.now(clock));
        db.jdbc.update("INSERT INTO customer(booking_id,booking_type) VALUES(?,'ADULT')",id);
        db.jdbc.update("UPDATE tour_departure SET available_seats=available_seats-1 WHERE id=?",departure);
        db.jdbc.update("INSERT INTO booking_reservation(booking_id,departure_id,departure_at,seats,status,expires_at,created_at,updated_at) VALUES(?,?,?,1,'HELD',?,?,?)",id,departure,at,LocalDateTime.now(clock).plusDays(6),LocalDateTime.now(clock),LocalDateTime.now(clock));
        return id;
    }
    ChangeRequests.Customer customer(int booking){return new ChangeRequests.Customer(UUID.randomUUID().toString(),booking,2,"Tôi muốn đổi thời gian để phù hợp công việc");}
    ChangeRequests.Shop shopInput(){return new ChangeRequests.Shop(UUID.randomUUID().toString(),1,db.jdbc.queryForObject("SELECT version FROM tour_departure WHERE id=1",Long.class),"NATURAL_DISASTER","Bão lớn khiến chuyến cũ không thể khởi hành an toàn","Thông báo đóng đường số TEST-123",true,start.plusDays(5),start.plusDays(7),10,start.minusDays(1));}
    Map<String,Object> proposal(){admin();return service.shop(shopInput(),false);}
    Map<String,Object> request(){return db.jdbc.queryForMap("SELECT * FROM booking_change WHERE kind='SHOP' ORDER BY id DESC LIMIT 1");}
    long id(Map<String,Object> r){return ((Number)r.get("id")).longValue();}
    ChangeRequests.Decision decision(Map<String,Object> r,String action){return new ChangeRequests.Decision(action,v(r),"Khách xác nhận qua hỗ trợ, bằng chứng TEST-123",null);}
    void conflict(Runnable run){assertEquals(409,assertThrows(ResponseStatusException.class,run::run).getStatusCode().value());}
    void missing(Runnable run){assertEquals(404,assertThrows(ResponseStatusException.class,run::run).getStatusCode().value());}
    int seats(int departure){return db.jdbc.queryForObject("SELECT available_seats FROM tour_departure WHERE id=?",Integer.class,departure);}
    @Test void cutoffIsExactlySeventyTwoHoursAndOnlyPaidOwnerCanRequest(){
        int b=booking(true);clock.set(start.minusHours(72));assertNotNull(service.customer(customer(b)));
        int other=booking(true);clock.set(start.minusHours(72).plusSeconds(1));conflict(()->service.customer(customer(other)));
        login(8);assertEquals(403,assertThrows(ResponseStatusException.class,()->service.options(b)).getStatusCode().value());
        login(7);clock.set(start.minusDays(10));int unpaid=booking(false);conflict(()->service.customer(customer(unpaid)));
    }
    @Test void requestDoesNotHoldTargetAndApprovalMovesOnceWithoutRewritingMoneyOrCreatedAt(){
        int b=booking(true);var created=db.jdbc.queryForMap("SELECT * FROM booking WHERE id=?",b).get("created_at");
        var input=customer(b);var r=service.customer(input);assertEquals(id(r),id(service.customer(input)));assertEquals(10,seats(2));
        clock.set(start.minusHours(20));admin();service.review(id(r),decision(r,"APPROVE"));service.review(id(r),decision(r,"APPROVE"));
        assertEquals(10,seats(1));assertEquals(9,seats(2));assertEquals(2,db.jdbc.queryForObject("SELECT tour_departure_id FROM booking WHERE id=?",Integer.class,b));
        assertEquals(created,db.jdbc.queryForMap("SELECT * FROM booking WHERE id=?",b).get("created_at"));
        assertEquals(3350000,db.jdbc.queryForObject("SELECT total_booking_tour_expense FROM payment WHERE booking_id=?",Integer.class,b));
    }
    @Test void targetFullPriceDifferenceAndPendingProviderOutcomeBlockChanges(){
        int b=booking(true);var r=service.customer(customer(b));db.jdbc.update("UPDATE tour_departure SET capacity=0,available_seats=0 WHERE id=2");
        admin();conflict(()->service.review(id(r),decision(r,"APPROVE")));assertEquals(9,seats(1));
        login(7);db.jdbc.update("UPDATE prices SET adult_price=4000000");conflict(()->service.customer(customer(b)));
        db.jdbc.update("UPDATE prices SET adult_price=3350000");
        db.jdbc.update("INSERT INTO vnpay_transaction(txn_ref,booking_id,status,expires_at) VALUES('pending',?,'PENDING',?)",b,start.minusDays(30));
        conflict(()->proposal());assertEquals("OPEN",db.jdbc.queryForObject("SELECT operational_status FROM tour_departure WHERE id=1",String.class));
    }
    @Test void pendingMomoBlocksScheduleChange() {
        int b=booking(true);
        db.jdbc.update("INSERT INTO momo_transaction(order_id,booking_id,status,created_at,expires_at) VALUES('MPENDING',?,'PENDING',?,?)",
                b,start.minusDays(1),start.minusDays(1).plusMinutes(15));
        conflict(this::proposal);
    }
    @Test void forcedShopChangeRequiresReasonEvidenceCapacityAndActualConsent(){
        booking(true);admin();var valid=shopInput();
        var invalid=new ChangeRequests.Shop(valid.clientKey(),1,0,"LOW_SALES",valid.reason(),valid.evidence(),true,valid.departureAt(),valid.returnAt(),10,valid.responseDeadline());
        conflict(()->service.shop(invalid,false));assertEquals(0,db.count("departure_change"));
        var preview=service.shop(valid,true);assertEquals(1,preview.get("bookingCount"));assertEquals(0,db.count("departure_change"));
        var root=service.shop(valid,false);assertEquals(id(root),id(service.shop(valid,false)));
        var r=request();var unaccepted=r;conflict(()->service.review(id(unaccepted),decision(unaccepted,"APPROVE")));conflict(()->service.activate(id(root)));
        login(8);assertEquals(403,assertThrows(ResponseStatusException.class,()->service.respond(id(unaccepted),decision(unaccepted,"ACCEPT"))).getStatusCode().value());
        login(7);r=service.respond(id(r),decision(r,"ACCEPT"));admin();service.review(id(r),decision(r,"APPROVE"));service.activate(id(root));
        assertEquals("CANCELLED",db.jdbc.queryForObject("SELECT operational_status FROM tour_departure WHERE id=1",String.class));
        assertEquals("OPEN",db.jdbc.queryForObject("SELECT operational_status FROM tour_departure WHERE id=?",String.class,n(root,"target_departure_id")));
    }
    @Test void suspendedOldHoldsDoNotExpireAndUnpaidTransferDoesNotResetOriginalDeadline(){
        int b=booking(false);proposal();clock.set(LocalDateTime.of(2026,11,10,10,0));expiry.sweep();
        assertEquals("HELD",db.jdbc.queryForObject("SELECT status FROM booking_reservation WHERE booking_id=?",String.class,b));assertEquals(9,seats(1));
        var r=request();login(7);r=service.respond(id(r),decision(r,"ACCEPT"));admin();var selected=r;
        conflict(()->service.review(id(selected),decision(selected,"APPROVE")));assertEquals(9,seats(1));
        service.review(id(selected),decision(selected,"CANCEL_UNPAID"));assertEquals(10,seats(1));
    }
    @Test void manualRefundRequiresCustomerRequestEvidenceAndExactMoneyAndIsIdempotent(){
        int b=booking(true);
        db.jdbc.update("INSERT INTO momo_transaction(order_id,booking_id,account_id,amount_vnd,status,created_at,expires_at) VALUES('MREFUND',?,'7',3350000,'PAID',?,?)",b,start,start.plusMinutes(15));
        var root=proposal();var r=request();
        var invalid=new ChangeRequests.Refund(v(r),true,new BigDecimal("3350000"),"BANK-TEST-1","Chứng từ hoàn tiền đã được kiểm tra");
        var pending=r;conflict(()->service.refund(id(pending),invalid));login(7);r=service.respond(id(r),decision(r,"REFUND"));admin();
        var selected=r;conflict(()->service.refund(id(selected),new ChangeRequests.Refund(v(selected),true,BigDecimal.ONE,"BANK-TEST-1",invalid.note())));
        var receipt=new ChangeRequests.Refund(v(r),true,new BigDecimal("3350000"),"BANK-TEST-1",invalid.note());
        service.refund(id(r),receipt);service.refund(id(r),receipt);assertEquals(10,seats(1));service.activate(id(root));
        assertEquals("REFUNDED",db.jdbc.queryForObject("SELECT status FROM payment WHERE booking_id=?",String.class,b));
        assertEquals("REFUNDED",db.jdbc.queryForObject("SELECT status FROM invoice WHERE booking_id=?",String.class,b));
        assertEquals("REFUNDED",db.jdbc.queryForObject("SELECT status FROM momo_transaction WHERE booking_id=?",String.class,b));
        assertEquals("CANCELLED",db.jdbc.queryForObject("SELECT status FROM booking WHERE id=?",String.class,b));
        assertTrue(db.count("booking_change_event")>=3);
    }
    @Test void earlierCustomerRequestIsSupersededAndNoResponseNeverMovesBooking(){
        int b=booking(true);service.customer(customer(b));var root=proposal();
        assertEquals(1,db.jdbc.queryForObject("SELECT COUNT(*) FROM booking_change WHERE status='SUPERSEDED'",Integer.class));
        clock.set(start.minusHours(12));login(7);var r=request();conflict(()->service.respond(id(r),decision(r,"ACCEPT")));
        admin();conflict(()->service.activate(id(root)));assertEquals(1,db.jdbc.queryForObject("SELECT tour_departure_id FROM booking WHERE id=?",Integer.class,b));
        service.review(id(r),decision(r,"RECORD_CONSENT"));
    }
    @Test void nonPrivilegedUserCannotReadAdminOrDeclareIncidents(){
        assertEquals(403,assertThrows(ResponseStatusException.class,()->service.admin(null,null,null)).getStatusCode().value());
        assertEquals(403,assertThrows(ResponseStatusException.class,()->service.shop(shopInput(),false)).getStatusCode().value());
    }
    @Test void obsoleteProposalsAndOldRemindersAreSuppressed(){
        booking(true);proposal();var r=request();
        String json=db.jdbc.queryForObject("SELECT payload FROM mail_outbox WHERE event_type='BOOKING_CHANGE_UPDATED' ORDER BY id DESC LIMIT 1",String.class);
        var mapper=JsonMapper.builder().findAndAddModules().build();
        try {
            var p=mapper.readValue(json,MailPayload.class);var item=new MailOutboxItem(1,MailEventType.BOOKING_CHANGE_UPDATED,n(r,"booking_id"),7,"tester@example.com",json,0,"test");
            assertNull(eligibility.skipReason(item,p));login(7);service.respond(id(r),decision(r,"ACCEPT"));assertNotNull(eligibility.skipReason(item,p));
            assertTrue(new ReminderStateReader(db.jdbc).read(n(r,"booking_id")).isEmpty());
        }catch(Exception ex){throw new AssertionError(ex);}
    }
    @Test void parallelApprovalsCannotOversellTheLastTargetSeat() throws Exception {
        int a=booking(true),b=booking(true);var ra=service.customer(customer(a));var rb=service.customer(customer(b));
        db.jdbc.update("UPDATE tour_departure SET capacity=1,available_seats=1 WHERE id=2");
        var pool=Executors.newFixedThreadPool(2);var startGate=new CountDownLatch(1);
        try {
            var tasks=new ArrayList<Future<Boolean>>();for(var r:List.of(ra,rb))tasks.add(pool.submit(()->{admin();startGate.await();try{service.review(id(r),decision(r,"APPROVE"));return true;}catch(ResponseStatusException conflict){return false;}finally{SecurityContextHolder.clearContext();}}));
            startGate.countDown();int success=0;for(var f:tasks)if(f.get(20,TimeUnit.SECONDS))success++;
            assertEquals(1,success);assertEquals(0,seats(2));assertEquals(9,seats(1));
        }finally{pool.shutdownNow();}
    }
    @Test void mailEnqueueFailureRollsBackRequestAuditAndInventory(){
        int b=booking(true);db.jdbc.update("UPDATE users SET email='invalid' WHERE id=7");
        assertThrows(IllegalArgumentException.class,()->service.customer(customer(b)));
        assertEquals(0,db.count("booking_change"));assertEquals(0,db.count("booking_change_event"));
        assertEquals(9,seats(1));assertEquals(10,seats(2));
    }
    @Test void validUnpaidMovePreservesOriginalBookingDateAndDeadline(){
        int b=booking(false);var root=proposal();var r=request();login(7);r=service.respond(id(r),decision(r,"ACCEPT"));
        admin();service.review(id(r),decision(r,"APPROVE"));
        var hold=db.jdbc.queryForMap("SELECT * FROM booking_reservation WHERE booking_id=?",b);
        assertEquals(LocalDateTime.of(2026,11,7,8,0),date(hold.get("expires_at")));
        assertEquals("HELD",hold.get("status"));assertEquals(10,seats(1));assertEquals(9,seats(n(root,"target_departure_id")));
    }
    @Test void badVersionAndUnsupportedTargetDoNotRecordConsent(){
        booking(true);proposal();var r=request();login(7);
        conflict(()->service.respond(id(r),new ChangeRequests.Decision("ACCEPT",v(r)+1,"Tôi đồng ý",null)));
        db.jdbc.update("UPDATE tour_departure SET tour_id=99 WHERE id=2");
        conflict(()->service.respond(id(r),new ChangeRequests.Decision("ACCEPT",v(r),"Tôi đồng ý",2)));
        assertEquals("PENDING",request().get("status"));assertNull(request().get("consent_source"));
    }
    @Test void targetAlternativeRequiresExplicitOwnerConsentAndStaysInSameTour(){
        booking(true);proposal();var r=request();login(7);
        var accepted=service.respond(id(r),new ChangeRequests.Decision("ACCEPT",v(r),"Tôi chọn lịch thay thế khác",2));
        assertEquals(2,n(accepted,"target_departure_id"));admin();service.review(id(r),decision(accepted,"APPROVE"));assertEquals(9,seats(2));
    }
    @Test void newBookingCannotUseSuspendedOrPrivateReplacementDeparture(){
        var root=proposal();login(7);
        for(int d:List.of(1,n(root,"target_departure_id"))){
            var request=com.example.booking_service.dto.request.BookingRequest.builder().userId("7").tourDepartureId(""+d)
                .listOfCustomers(List.of(com.example.booking_service.dto.request.CustomerRequest.builder().fullName("Test").birthdate("2000-01-01").gender("Male").build())).build();
            conflict(()->lifecycle.create(request));
        }
        assertEquals(0,db.count("booking"));
    }
    @Test void adminCannotReadOtherOwnersRequestsThroughCustomerEndpoint(){
        int b=booking(true);service.customer(customer(b));admin();assertTrue(service.mine().isEmpty());
        login(8,"PERM:CHANGES_READ");assertEquals(1,((List<?>)service.admin(null,null,null).get("requests")).size());
        assertEquals(403,assertThrows(ResponseStatusException.class,()->service.review(1,new ChangeRequests.Decision("APPROVE",0,"Tôi duyệt yêu cầu",null))).getStatusCode().value());
    }
    @Test void changedPriceAfterRequestRequiresSupportWithoutChangingPaidAmount(){
        int b=booking(true);var r=service.customer(customer(b));db.jdbc.update("UPDATE prices SET adult_price=4000000");admin();
        conflict(()->service.review(id(r),decision(r,"APPROVE")));assertEquals(9,seats(1));assertEquals(10,seats(2));
        assertEquals(3350000,db.jdbc.queryForObject("SELECT total_booking_tour_expense FROM payment WHERE booking_id=?",Integer.class,b));
    }
    @Test void reminderMilestonesBeforeActualMoveAreNotSentAsCatchup(){
        int b=booking(true);var r=service.customer(customer(b));clock.set(start.plusDays(8).withHour(10));
        // An approval after source departure must fail, even if the target is still future.
        admin();conflict(()->service.review(id(r),decision(r,"APPROVE")));
        clock.set(start.minusDays(1));service.review(id(r),decision(r,"APPROVE"));
        var current=new ReminderStateReader(db.jdbc).read(b).orElseThrow();assertEquals(LocalDateTime.now(clock),current.createdAt());
        assertTrue(new ReminderRules().select(current,LocalDateTime.now(clock)).isEmpty());
    }
    @Test void httpDatesPreserveVietnamWallClockAndConflictDoesNotBecome500() throws Exception {
        int b=booking(true);service.customer(customer(b));
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new BookingChangeController(service))
            .setControllerAdvice(new com.example.booking_service.exception.GlobalExceptionHandler()).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/changes/mine"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.result[0].source_departure_at").value(start.toString()));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/changes/customer").contentType("application/json")
            .content("{\"clientKey\":\""+UUID.randomUUID()+"\",\"bookingId\":"+b+",\"targetId\":2,\"reason\":\"Tôi muốn đổi lịch lần nữa\"}"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
    }
    @Test void unknownDepartureAndChangeIdsAnswerNotFoundAndPreviewNeverWrites() throws Exception {
        int b=booking(true);
        missing(()->service.customer(new ChangeRequests.Customer(UUID.randomUUID().toString(),b,999,"Tôi muốn đổi lịch cho phù hợp công việc hơn")));
        admin();
        // The impact preview must not lock the departure it is only reporting on.
        var preview=service.shop(new ChangeRequests.Shop(UUID.randomUUID().toString(),1,
            db.jdbc.queryForObject("SELECT version FROM tour_departure WHERE id=1",Long.class),
            "NATURAL_DISASTER","Bão lớn khiến chuyến cũ không thể khởi hành an toàn",
            "Thông báo đóng đường số TEST-123",true,start.plusDays(5),start.plusDays(7),10,start.minusDays(1)),true);
        assertEquals(1,preview.get("bookingCount"));assertEquals(0,db.count("departure_change"));
        missing(()->service.shop(new ChangeRequests.Shop(UUID.randomUUID().toString(),999,0L,
            "NATURAL_DISASTER","Bão lớn khiến chuyến cũ không thể khởi hành an toàn",
            "Thông báo đóng đường số TEST-123",true,start.plusDays(5),start.plusDays(7),10,start.minusDays(1)),true));
        missing(()->service.respond(9999,new ChangeRequests.Decision("ACCEPT",0,"Tôi đồng ý",null)));
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new BookingChangeController(service))
            .setControllerAdvice(new com.example.booking_service.exception.GlobalExceptionHandler()).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/changes/shop/9999/activate"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
    }
    @Test void paidReminderBeforeLatestMoveIsNeverSentAsCatchup(){
        int b=booking(true);
        db.jdbc.update("UPDATE tour_departure SET departure_date=?,return_date=? WHERE id=2",start.minusDays(10),start.minusDays(8));
        var r=service.customer(customer(b));clock.set(start.minusDays(12).withHour(10));admin();service.review(id(r),decision(r,"APPROVE"));
        var facts=new ReminderStateReader(db.jdbc).read(b).orElseThrow();
        assertTrue(new ReminderRules().select(facts,LocalDateTime.now(clock)).isEmpty());
        clock.set(start.minusDays(11).withHour(8));
        assertEquals(1,new ReminderRules().select(new ReminderStateReader(db.jdbc).read(b).orElseThrow(),LocalDateTime.now(clock)).orElseThrow().daysBefore());
    }
    @Test void queuedApprovalBecomesObsoleteWhenTheNewTripIsSuspendedAgain() throws Exception {
        int b=booking(true);var r=service.customer(customer(b));admin();service.review(id(r),decision(r,"APPROVE"));
        String json=db.jdbc.queryForObject("SELECT payload FROM mail_outbox WHERE event_type='BOOKING_CHANGE_UPDATED' ORDER BY id DESC LIMIT 1",String.class);
        var payload=JsonMapper.builder().findAndAddModules().build().readValue(json,MailPayload.class);
        var item=new MailOutboxItem(1,MailEventType.BOOKING_CHANGE_UPDATED,b,7,"tester@example.com",json,0,"test");
        assertNull(eligibility.skipReason(item,payload));
        db.jdbc.update("UPDATE tour_departure SET operational_status='SUSPENDED' WHERE id=2");
        assertNotNull(eligibility.skipReason(item,payload));
    }
    @Test void pendingPaymentHoldsOnSourceAndTargetDoNotBlockAReschedule(){
        int b=booking(true);int sourceHold=pendingBooking(1);int targetHold=pendingBooking(2);
        var r=service.customer(customer(b));assertEquals("PENDING",r.get("status"));
        clock.set(start.minusHours(20));admin();service.review(id(r),decision(r,"APPROVE"));
        assertEquals(2,db.jdbc.queryForObject("SELECT tour_departure_id FROM booking WHERE id=?",Integer.class,b));
        for(int pending:List.of(sourceHold,targetHold))
            assertEquals("PENDING_PAYMENT",db.jdbc.queryForObject("SELECT status FROM booking WHERE id=?",String.class,pending));
    }
    @Test void staleUnpaidHoldOnCancelledSourceIsReleasedAndExpired(){
        booking(true);int pending=pendingBooking(1);
        var root=proposal();var r=request();login(7);r=service.respond(id(r),decision(r,"ACCEPT"));
        admin();service.review(id(r),decision(r,"APPROVE"));service.activate(id(root));
        assertEquals("CANCELLED",db.jdbc.queryForObject("SELECT operational_status FROM tour_departure WHERE id=1",String.class));
        assertEquals("HELD",db.jdbc.queryForObject("SELECT status FROM booking_reservation WHERE booking_id=?",String.class,pending));
        clock.set(start.plusDays(8));expiry.sweep();
        assertEquals("EXPIRED",db.jdbc.queryForObject("SELECT status FROM booking WHERE id=?",String.class,pending));
        assertEquals("EXPIRED",db.jdbc.queryForObject("SELECT status FROM booking_reservation WHERE booking_id=?",String.class,pending));
        assertEquals(10,seats(1));
    }
}
