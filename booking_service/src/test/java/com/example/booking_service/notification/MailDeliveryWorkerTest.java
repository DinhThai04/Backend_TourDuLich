package com.example.booking_service.notification;

import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MailDeliveryWorkerTest {
    MailTestDatabase db;
    MailProperties props;
    SpringMailSender sender;
    MailTemplateRenderer renderer;
    MailEligibility eligibility;
    MailDeliveryWorker worker;
    Clock clock = Clock.fixed(Instant.parse("2026-10-14T01:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));
    @BeforeEach void setup() throws Exception {
        db = new MailTestDatabase(); props = new MailProperties(); props.setEnabled(true);
        // Test mode only delivers to the configured mailbox, so the fixture recipient must be it.
        props.setTestRecipient("tester@example.com");
        sender = mock(SpringMailSender.class); renderer = mock(MailTemplateRenderer.class); eligibility = mock(MailEligibility.class);
        when(renderer.decode(any())).thenReturn(MailPayload.test());
        when(renderer.render(any(), any())).thenReturn(new MailTemplateRenderer.RenderedMail("Test", "Text", "<p>Thử</p>"));
        worker = new MailDeliveryWorker(db.repository, renderer, eligibility, sender, props, clock);
        new MailOutboxService(db.repository, JsonMapper.builder().findAndAddModules().build(), clock)
                .enqueue("test:worker", MailEventType.TEST_MAIL, "booking_service", null, null,
                        "tester@example.com", MailPayload.test(), LocalDateTime.now(clock));
    }
    String status() { return db.jdbc.queryForObject("SELECT status FROM mail_outbox", String.class); }
    @Test void sendsOnceAcrossRepeatedPolls() throws Exception {
        worker.poll(); worker.poll(); assertEquals("SENT", status()); verify(sender, times(1)).send(anyString(), any());
        assertEquals(1, db.jdbc.queryForObject("SELECT attempt_count FROM mail_outbox", Integer.class));
    }
    @Test void disabledDoesNotReadClaimOrSend() throws Exception {
        props.setEnabled(false); db.jdbc.execute("DROP TABLE mail_outbox");
        worker.poll(); verifyNoInteractions(sender);
    }
    @Test void otherRecipientsAreSkippedWithoutRedirecting() throws Exception {
        db.jdbc.update("UPDATE mail_outbox SET recipient='user1@gmail.com'");
        worker.poll(); assertEquals("SKIPPED", status()); verifyNoInteractions(sender);
    }
    @Test void staleBusinessEventIsSkipped() {
        when(eligibility.skipReason(any(), any())).thenReturn("Booking already paid");
        worker.poll(); assertEquals("SKIPPED", status()); verifyNoInteractions(sender);
    }
    @Test void malformedPayloadIsFailedBeforeSmtp() {
        when(renderer.decode(any())).thenThrow(new IllegalArgumentException("bad JSON"));
        worker.poll(); assertEquals("FAILED", status()); verifyNoInteractions(sender);
    }
    @Test void eligibilityDatabaseOutageDoesNotPermanentlyDiscardMail() {
        when(eligibility.skipReason(any(), any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("down"));
        worker.poll(); assertEquals("CLAIMED", status()); verifyNoInteractions(sender);
    }
    @Test void authenticationFailureIsNotBlindlyRetried() throws Exception {
        doThrow(new MailAuthenticationException("secret must not be stored")).when(sender).send(anyString(), any());
        worker.poll(); assertEquals("FAILED", status());
        assertFalse(db.jdbc.queryForObject("SELECT last_error FROM mail_outbox", String.class).contains("secret"));
    }
    @Test void connectionRefusedIsRetriedWithBackoff() throws Exception {
        doThrow(new MailSendException("connect", new ConnectException("refused"))).when(sender).send(anyString(), any());
        worker.poll(); assertEquals("RETRY", status());
        assertEquals(LocalDateTime.now(clock).plusMinutes(10), db.jdbc.queryForObject("SELECT next_attempt_at FROM mail_outbox", LocalDateTime.class));
        worker.poll(); verify(sender, times(1)).send(anyString(), any());
    }
    @Test void lastKnownUnsentAttemptBecomesFailed() throws Exception {
        db.jdbc.update("UPDATE mail_outbox SET attempt_count=2");
        doThrow(new MailSendException("connect", new ConnectException("refused"))).when(sender).send(anyString(), any());
        worker.poll(); assertEquals("FAILED", status());
    }
    @Test void socketTimeoutHasUncertainOutcomeAndIsNotRetried() throws Exception {
        doThrow(new MailSendException("timeout", new SocketTimeoutException("read"))).when(sender).send(anyString(), any());
        worker.poll(); worker.poll(); assertEquals("UNKNOWN", status()); verify(sender, times(1)).send(anyString(), any());
    }
    @Test void perMessageFailureIsConservativeEvenIfNestedConnectErrorExists() throws Exception {
        doThrow(new MailSendException("partial", new ConnectException("refused"), Map.of("message", new Exception())))
                .when(sender).send(anyString(), any());
        worker.poll(); assertEquals("UNKNOWN", status());
    }
    @Test void lostAcknowledgementDoesNotResendAcceptedEmail() throws Exception {
        var repository = spy(db.repository);
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("database lost"))
                .when(repository).finish(any(), any(), any(), any(), any());
        var withAckFailure = new MailDeliveryWorker(repository, renderer, eligibility, sender, props, clock);
        withAckFailure.poll(); withAckFailure.poll();
        assertEquals("DISPATCHING", status()); verify(sender, times(1)).send(anyString(), any());
    }
}
