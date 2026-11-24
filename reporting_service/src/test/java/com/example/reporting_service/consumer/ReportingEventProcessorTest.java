package com.example.reporting_service.consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
class ReportingEventProcessorTest {

    @Autowired
    private ReportingEventProcessor processor;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM reporting_inbox");
        jdbc.update("DELETE FROM operation_audit");
    }

    @Test
    void persistsInboxAndSanitizedAuditAtomicallyAndIgnoresRedelivery() throws Exception {
        String eventId = "4db2988f-4030-43b1-80df-304ab08a80aa";
        Message event = message(eventId, "booking.created.v1", """
                {"eventId":"%s","eventType":"booking.created.v1",
                 "occurredAt":"2026-11-23T12:00:00+07:00","producer":"booking-service",
                 "aggregateType":"booking","aggregateId":"301","correlationId":"corr-1",
                 "payload":{"bookingId":301,"email":"customer@example.test"}}
                """.formatted(eventId));

        assertTrue(processor.process(event));
        assertFalse(processor.process(event));

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM reporting_inbox", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM operation_audit", Integer.class));
        assertEquals("booking-service", jdbc.queryForObject("SELECT actor_id FROM operation_audit", String.class));
        assertEquals("booking.created.v1", jdbc.queryForObject("SELECT action FROM operation_audit", String.class));
        String detail = jdbc.queryForObject("SELECT detail FROM operation_audit", String.class);
        assertTrue(detail.contains("301"));
        assertFalse(detail.contains("customer@example.test"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM reporting_inbox WHERE processed_at IS NOT NULL", Integer.class));
    }

    @Test
    void supportsLegacyPaymentOutboxPayloadWhenHeadersCarryMetadata() throws Exception {
        String eventId = "6c003ba3-5e49-46d6-ae0e-429ac4c88c11";
        MessageProperties properties = properties(eventId, "payment.settled.v1");
        properties.setHeader("source-service", "payment-service");
        properties.setHeader("aggregate_type", "Payment");
        properties.setHeader("aggregate_id", "99");
        properties.setHeader("correlation_id", "corr-2");
        Message event = new Message(("""
                {"eventId":"%s","eventType":"payment.settled.v1",
                 "occurredAt":"2026-11-23T04:30:00Z","bookingId":99,
                 "accountId":7,"amountVnd":1000,"providerRef":"provider-ref"}
                """).formatted(eventId).getBytes(), properties);

        assertTrue(processor.process(event));
        assertEquals("payment-service", jdbc.queryForObject("SELECT actor_id FROM operation_audit", String.class));
        assertEquals("Payment", jdbc.queryForObject("SELECT entity_type FROM operation_audit", String.class));
        assertEquals("99", jdbc.queryForObject("SELECT entity_id FROM operation_audit", String.class));
        String detail = jdbc.queryForObject("SELECT detail FROM operation_audit", String.class);
        assertFalse(detail.contains("provider-ref"));
    }

    @Test
    void rejectsMessageAndBodyIdentityMismatch() throws Exception {
        Message event = message("6c003ba3-5e49-46d6-ae0e-429ac4c88c11", "booking.created.v1",
                """
                        {"eventId":"another-id","eventType":"booking.created.v1"}
                        """);

        assertThrows(IllegalArgumentException.class, () -> processor.process(event));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM reporting_inbox", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM operation_audit", Integer.class));
    }

    private Message message(String eventId, String eventType, String body) throws Exception {
        return new Message(body.getBytes(), properties(eventId, eventType));
    }

    private MessageProperties properties(String eventId, String eventType) {
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(eventId);
        properties.setType(eventType);
        properties.setContentType("application/json");
        return properties;
    }
}
