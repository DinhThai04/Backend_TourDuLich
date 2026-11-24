package com.example.notification_service.consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
class NotificationEventProcessorTest {

    @Autowired
    private NotificationEventProcessor processor;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM mail_outbox");
        jdbc.update("DELETE FROM notification_inbox");
        jdbc.update("DELETE FROM notification_dead_letter");
        jdbc.update("DELETE FROM mail_template");
        jdbc.update("""
                INSERT INTO mail_template(id,code,subject,body_html,body_text,variables,created_at,updated_at)
                VALUES(?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, UUID.randomUUID().toString(), "BOOKING_CREATED", "Hello {{customerName}}",
                "<p>Hello {{customerName}}</p>", "Hello {{customerName}}", null);
    }

    @Test
    void storesMailAndInboxAtomicallyAndDeduplicatesRedelivery() throws Exception {
        String eventId = UUID.randomUUID().toString();
        Message message = event(eventId, "booking.created.v1", "user@example.com", "<script>alert(1)</script>");

        assertTrue(processor.process(message));
        assertFalse(processor.process(message));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM notification_inbox", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM mail_outbox", Integer.class));
        String payload = jdbc.queryForObject("SELECT payload FROM mail_outbox", String.class);
        assertEquals("<p>Hello &lt;script&gt;alert(1)&lt;/script&gt;</p>", objectMapper.readTree(payload).path("html").asText());
    }

    @Test
    void rejectsInvalidRecipientWithoutCommittingInboxReceipt() {
        Message message = event(UUID.randomUUID().toString(), "booking.created.v1", "victim@example.com\r\nBcc:x@example.com", "A");

        assertThrows(InvalidNotificationEventException.class, () -> processor.process(message));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM notification_inbox", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM mail_outbox", Integer.class));
    }

    @Test
    void rejectsMessageAndBodyIdentityMismatch() {
        String eventId = UUID.randomUUID().toString();
        Message message = event(eventId, "booking.created.v1", "user@example.com", "A");
        message.getMessageProperties().setMessageId(UUID.randomUUID().toString());

        assertThrows(InvalidNotificationEventException.class, () -> processor.process(message));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM notification_inbox", Integer.class));
    }

    private Message event(String eventId, String eventType, String recipient, String customerName) {
        String body = """
                {"eventId":"%s","eventType":"%s","producer":"booking-service","payload":{
                  "recipient":"%s","bookingId":100,"accountId":7,"customerName":"%s"}}
                """.formatted(eventId, eventType, recipient, customerName);
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(eventId);
        properties.setType(eventType);
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setHeader("source-service", "booking-service");
        return new Message(body.getBytes(StandardCharsets.UTF_8), properties);
    }
}
