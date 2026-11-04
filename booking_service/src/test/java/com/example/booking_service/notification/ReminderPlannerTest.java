package com.example.booking_service.notification;

import com.example.booking_service.reservation.LifecycleTestDatabase;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ReminderPlannerTest {
    LifecycleTestDatabase db;
    LifecycleTestDatabase.MutableClock clock;
    ReminderPlanner planner;
    MailProperties properties;
    MailOutboxRepository repository;
    @BeforeEach void setup() throws Exception {
        db=new LifecycleTestDatabase();clock=new LifecycleTestDatabase.MutableClock(LocalDateTime.of(2026,11,4,8,0));
        properties=new MailProperties();
        // The fixture customer (account 7) owns this mailbox, so test mode keeps it eligible.
        properties.setTestRecipient("tester@example.com");
        repository=new MailOutboxRepository(db.jdbc,db.manager);
        planner=new ReminderPlanner(db.jdbc,new MailOutboxService(repository,JsonMapper.builder().findAndAddModules().build(),clock),properties,clock,db.manager);
        unpaid(1,7);
    }
    void unpaid(int id,int account) {
        db.jdbc.update("INSERT INTO booking (id,account_id,tour_departure_id,status,created_at) VALUES (?,?,1,'CONFIRMED',?)",id,account,LocalDateTime.of(2026,11,1,10,0));
        db.jdbc.update("INSERT INTO customer (booking_id,booking_type) VALUES (?,'ADULT')",id);
        db.jdbc.update("""
            INSERT INTO booking_reservation (booking_id,departure_id,departure_at,seats,status,expires_at,reminder_one_at,reminder_two_at,created_at,updated_at)
            VALUES (?,1,?,1,'HELD',?,?,?,?,?)
            """,id,LocalDateTime.of(2026,11,20,6,0),LocalDateTime.of(2026,11,7,8,0),LocalDateTime.of(2026,11,4,8,0),
                LocalDateTime.of(2026,11,6,8,0),LocalDateTime.of(2026,11,1,10,0),LocalDateTime.of(2026,11,1,10,0));
    }
    void paid(LocalDateTime at) {
        db.jdbc.update("UPDATE booking_reservation SET status='PAID' WHERE booking_id=1");
        for(String table:new String[]{"payment","invoice"}) db.jdbc.update("INSERT INTO "+table+" (booking_id,account_id,status,day_of_pay,total_booking_tour_expense) VALUES (1,7,'PAID',?,3350000)",at);
    }
    ReminderPlanner.Result dispatch(boolean dry) { return planner.dispatch(new ReminderPlanner.Request(dry,100,0)); }
    MailOutboxItem current() { return repository.claimNext(LocalDateTime.now(clock),LocalDateTime.now(clock).plusMinutes(2)).orElseThrow(); }
    MailPayload decode(MailOutboxItem item) throws Exception { return JsonMapper.builder().findAndAddModules().build().readValue(item.payload(),MailPayload.class); }
    @Test void dryRunDoesNotWriteOrChangeBookingSeats() {
        assertEquals(1,dispatch(true).eligible());assertEquals(0,db.count("mail_outbox"));assertEquals(10,db.seats());
    }
    @Test void unpaidReminderHasRealDeadlineAndDoesNotDuplicate() {
        assertEquals(1,dispatch(false).created());assertEquals(1,dispatch(false).alreadyExists());assertEquals(1,db.count("mail_outbox"));
        assertTrue(db.jdbc.queryForObject("SELECT payload FROM mail_outbox",String.class).contains("reminderAt"));
    }
    @Test void notDueBeforeEightOrAfterDeadline() {
        clock.set(LocalDateTime.of(2026,11,4,7,59));assertEquals(0,dispatch(false).created());
        clock.set(LocalDateTime.of(2026,11,7,8,0));assertEquals(0,dispatch(false).created());
    }
    @Test void secondMilestoneSupersedesPendingFirstEvenAfterDowntime() throws Exception {
        dispatch(false);var first=current();var payload=decode(first);
        clock.set(LocalDateTime.of(2026,11,6,8,0));assertEquals(1,dispatch(false).created());
        assertNotNull(new MailEligibility(db.jdbc,clock).skipReason(first,payload));assertEquals(2,db.count("mail_outbox"));
    }
    @Test void paidOrCancelledBookingNeverReceivesUnpaidReminder() {
        paid(LocalDateTime.of(2026,11,2,10,0));assertEquals(0,dispatch(false).created());
        db.jdbc.update("DELETE FROM payment");db.jdbc.update("DELETE FROM invoice");db.jdbc.update("UPDATE booking SET status='CANCELLED'");
        assertEquals(0,dispatch(false).created());
    }
    @Test void departureRemindsFiveThreeAndOneDaysAndStopsOnDepartureDate() {
        paid(LocalDateTime.of(2026,11,2,10,0));
        for(int day:new int[]{15,17,19}) {clock.set(LocalDateTime.of(2026,11,day,8,0));assertEquals(1,dispatch(false).created());}
        assertEquals(3,db.count("mail_outbox"));clock.set(LocalDateTime.of(2026,11,20,0,0));assertEquals(0,dispatch(false).eligible());
    }
    @Test void latePaymentReceivesOnlyFutureMilestones() {
        paid(LocalDateTime.of(2026,11,18,10,0));clock.set(LocalDateTime.of(2026,11,18,11,0));assertEquals(0,dispatch(false).created());
        clock.set(LocalDateTime.of(2026,11,19,8,0));assertEquals(1,dispatch(false).created());
    }
    @Test void downtimeCreatesOnlyMostRecentMilestoneAndOldOneIsInvalidated() throws Exception {
        paid(LocalDateTime.of(2026,11,2,10,0));clock.set(LocalDateTime.of(2026,11,18,8,0));dispatch(false);
        var old=current();assertEquals(3,decode(old).daysBefore());clock.set(LocalDateTime.of(2026,11,19,7,59));
        assertNotNull(new MailEligibility(db.jdbc,clock).skipReason(old,decode(old)));
        assertEquals(0,dispatch(false).eligible());clock.set(LocalDateTime.of(2026,11,19,8,0));assertEquals(1,dispatch(false).created());
    }
    @Test void inconsistentPaymentsOrReconciliationBlockReminder() {
        paid(LocalDateTime.of(2026,11,2,10,0));clock.set(LocalDateTime.of(2026,11,15,8,0));
        db.jdbc.update("UPDATE invoice SET total_booking_tour_expense=1");assertEquals(0,dispatch(false).created());
        db.jdbc.update("UPDATE invoice SET total_booking_tour_expense=3350000");
        db.jdbc.update("INSERT INTO payment_reconciliation (txn_ref,booking_id,reason,status,provider_paid_at,amount_vnd,created_at) VALUES ('review',1,'TEST','OPEN',?,3350000,?)",LocalDateTime.now(clock),LocalDateTime.now(clock));
        assertEquals(0,dispatch(false).created());
    }
    @Test void testModeDoesNotQueueOtherMailboxes() {
        unpaid(2,8);assertEquals(1,dispatch(false).created());assertEquals(1,dispatch(false).skipped());
    }
    @Test void paginationVisitsEveryBookingWithoutStarvingLaterPages() {
        unpaid(2,7);unpaid(3,7);
        var first=planner.dispatch(new ReminderPlanner.Request(false,1,0));assertTrue(first.hasMore());assertEquals(1,first.nextCursor());
        var second=planner.dispatch(new ReminderPlanner.Request(false,1,first.nextCursor()));assertTrue(second.hasMore());assertEquals(2,second.nextCursor());
        var third=planner.dispatch(new ReminderPlanner.Request(false,1,second.nextCursor()));assertFalse(third.hasMore());assertEquals(3,db.count("mail_outbox"));
    }
    @Test void invalidRecipientDoesNotBlockNextPageOrQueueInvalidMail() {
        properties.setTestMode(false);
        db.jdbc.update("UPDATE users SET email='invalid-address' WHERE id=7");
        unpaid(2,8);
        var first=planner.dispatch(new ReminderPlanner.Request(false,1,0));
        assertEquals(0,first.failed());assertEquals(1,first.skipped());assertTrue(first.hasMore());
        assertEquals(1,first.invalidRecipients());
        assertEquals(0,db.count("mail_outbox"));
        var second=planner.dispatch(new ReminderPlanner.Request(false,1,first.nextCursor()));
        assertEquals(1,second.created());assertFalse(second.hasMore());assertEquals(1,db.count("mail_outbox"));
    }
    @Test void databaseFailureIsStillAnErrorRatherThanARecipientWarning() {
        db.jdbc.execute("DROP TABLE customer");
        assertThrows(org.springframework.dao.DataAccessException.class,()->dispatch(false));
        assertEquals(0,db.count("mail_outbox"));
    }
    @Test void concurrentDispatchIsIdempotent() throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->dispatch(false));var b=pool.submit(()->dispatch(false));
            assertEquals(1,a.get(10,TimeUnit.SECONDS).created()+b.get(10,TimeUnit.SECONDS).created());
        }
        assertEquals(1,db.count("mail_outbox"));
    }
    @Test void changingDepartureInvalidatesPreviouslyQueuedReminder() throws Exception {
        dispatch(false);var old=current();db.jdbc.update("UPDATE tour_departure SET departure_date=?",LocalDateTime.of(2026,11,21,6,0));
        assertNotNull(new MailEligibility(db.jdbc,clock).skipReason(old,decode(old)));assertEquals(0,dispatch(false).created());
    }
    @Test void legacyPaidBookingCanReceiveDepartureReminderWithoutBackfilledHold() {
        paid(LocalDateTime.of(2026,11,2,10,0));db.jdbc.update("DELETE FROM booking_reservation");
        clock.set(LocalDateTime.of(2026,11,15,8,0));assertEquals(1,dispatch(false).created());
    }
    @Test void malformedLimitsAreRejected() {
        assertThrows(IllegalArgumentException.class,()->new ReminderPlanner.Request(false,201,0));
        assertThrows(IllegalArgumentException.class,()->new ReminderPlanner.Request(false,100,-1));
    }
    @Test void combinedSetupCanBeImportedAgainWithoutResettingMailOrSeats() throws Exception {
        dispatch(false);db.run("db/001_mail_outbox.sql",System.getenv("LIFECYCLE_TEST_JDBC_URL")!=null);db.run("db/003_booking_reservation.sql",System.getenv("LIFECYCLE_TEST_JDBC_URL")!=null);
        assertEquals(1,db.count("mail_outbox"));assertEquals(10,db.seats());assertEquals(1,db.count("booking_reservation"));
    }
}
