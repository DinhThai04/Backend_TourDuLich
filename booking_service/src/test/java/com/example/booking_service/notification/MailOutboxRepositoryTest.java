package com.example.booking_service.notification;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDateTime;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class MailOutboxRepositoryTest {
    MailTestDatabase db;
    LocalDateTime now = LocalDateTime.of(2026, 10, 14, 8, 0);
    @BeforeEach void setup() throws Exception { db = new MailTestDatabase(); }
    boolean enqueue(String key, LocalDateTime due) {
        return db.repository.insert(key, MailEventType.TEST_MAIL, "booking_service", null, null,
                "tester@example.com", "{\"version\":1}", due, now);
    }
    String status() { return db.jdbc.queryForObject("SELECT status FROM mail_outbox", String.class); }

    @Test void uniqueEventIsNotInsertedTwice() {
        assertTrue(enqueue("test:1", now)); assertFalse(enqueue("test:1", now));
        assertEquals(1, db.jdbc.queryForObject("SELECT COUNT(*) FROM mail_outbox", Integer.class));
    }
    @Test void eventRollsBackWithBusinessTransaction() {
        new TransactionTemplate(db.transactions).execute(tx -> {
            enqueue("test:rollback", now); tx.setRollbackOnly(); return null;
        });
        assertEquals(0, db.jdbc.queryForObject("SELECT COUNT(*) FROM mail_outbox", Integer.class));
    }
    @Test void scheduledAndBackoffDatesAreRespected() {
        enqueue("test:future", now.plusHours(1));
        assertTrue(db.repository.claimNext(now, now.plusMinutes(2)).isEmpty());
        var item = db.repository.claimNext(now.plusHours(1), now.plusHours(1).plusMinutes(2)).orElseThrow();
        assertTrue(db.repository.finish(item, MailDeliveryStatus.RETRY, "connect", now.plusHours(2), now.plusHours(1)));
        assertTrue(db.repository.claimNext(now.plusHours(1), now.plusHours(1).plusMinutes(2)).isEmpty());
    }
    @Test void onlyOneWorkerClaimsTheSameMail() throws Exception {
        enqueue("test:race", now);
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch gate = new CountDownLatch(1);
            Callable<Boolean> claim = () -> { gate.await(); return db.repository.claimNext(now, now.plusMinutes(2)).isPresent(); };
            var a = pool.submit(claim); var b = pool.submit(claim); gate.countDown();
            assertNotEquals(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
        }
    }
    @Test void expiredClaimCanBeRetriedButOldTokenCannotSend() {
        enqueue("test:claim", now);
        var old = db.repository.claimNext(now, now.plusMinutes(2)).orElseThrow();
        db.repository.recoverExpired(now.plusMinutes(3));
        var fresh = db.repository.claimNext(now.plusMinutes(3), now.plusMinutes(5)).orElseThrow();
        assertNotEquals(old.claimToken(), fresh.claimToken());
        assertFalse(db.repository.start(old, now.plusMinutes(3), now.plusMinutes(5)));
        assertTrue(db.repository.start(fresh, now.plusMinutes(3), now.plusMinutes(5)));
    }
    @Test void expiredDispatchIsUnknownAndNeverAutomaticallyClaimedAgain() {
        enqueue("test:dispatch", now);
        var item = db.repository.claimNext(now, now.plusMinutes(2)).orElseThrow();
        assertTrue(db.repository.start(item, now, now.plusMinutes(2)));
        db.repository.recoverExpired(now.plusMinutes(3));
        assertEquals("UNKNOWN", status());
        assertTrue(db.repository.claimNext(now.plusMinutes(3), now.plusMinutes(5)).isEmpty());
        assertFalse(db.repository.finish(item, MailDeliveryStatus.SENT, null, now, now.plusMinutes(3)));
    }
    @Test void sentAcknowledgementIsIdempotentAndNotClaimable() {
        enqueue("test:sent", now);
        var item = db.repository.claimNext(now, now.plusMinutes(2)).orElseThrow();
        db.repository.start(item, now, now.plusMinutes(2));
        assertTrue(db.repository.finish(item, MailDeliveryStatus.SENT, null, now, now));
        assertFalse(db.repository.finish(item, MailDeliveryStatus.RETRY, "late", now, now));
        assertEquals("SENT", status());
        assertNotNull(db.jdbc.queryForObject("SELECT sent_at FROM mail_outbox", LocalDateTime.class));
        assertTrue(db.repository.claimNext(now, now.plusMinutes(2)).isEmpty());
    }
    @Test void duplicateEventDoesNotUndoOtherWorkInTransaction() {
        enqueue("test:dupe", now);
        new TransactionTemplate(db.transactions).execute(tx -> {
            assertFalse(enqueue("test:dupe", now)); enqueue("test:other", now); return null;
        });
        assertEquals(2, db.jdbc.queryForObject("SELECT COUNT(*) FROM mail_outbox", Integer.class));
    }
    @Test void schemaAndTestFixtureCanBeLoadedTwiceWithoutResending() throws Exception {
        db.jdbc.execute(db.ddl);
        String testSeed = new org.springframework.core.io.ClassPathResource("db/002_enqueue_test_mail.sql").getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        String insert = testSeed.substring(0, testSeed.indexOf(';') + 1);
        db.jdbc.execute(insert);
        db.jdbc.update("UPDATE mail_outbox SET status='SENT'");
        db.jdbc.execute(insert);
        assertEquals(1, db.jdbc.queryForObject("SELECT COUNT(*) FROM mail_outbox", Integer.class));
        assertEquals("SENT", status());
        assertEquals("tester@example.com", db.jdbc.queryForObject("SELECT recipient FROM mail_outbox", String.class));
    }
}
