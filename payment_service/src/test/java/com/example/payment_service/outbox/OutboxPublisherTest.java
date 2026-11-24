package com.example.payment_service.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class OutboxPublisherTest {

    private JdbcTemplate jdbc;
    private RabbitTemplate rabbit;
    private OutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:outbox_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE payment_outbox (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    event_id VARCHAR(36) NOT NULL,
                    event_type VARCHAR(120) NOT NULL,
                    aggregate_type VARCHAR(80) NOT NULL,
                    aggregate_id VARCHAR(80) NOT NULL,
                    aggregate_version INT NOT NULL,
                    correlation_id VARCHAR(80),
                    causation_id VARCHAR(80),
                    payload VARCHAR(4000) NOT NULL,
                    status VARCHAR(20) NOT NULL,
                    attempts INT NOT NULL,
                    available_at TIMESTAMP NOT NULL,
                    published_at TIMESTAMP,
                    last_error VARCHAR(1000),
                    created_at TIMESTAMP NOT NULL
                )
                """);
        rabbit = mock(RabbitTemplate.class);
        publisher = new OutboxPublisher(rabbit, jdbc);
    }

    @Test
    void usesSameEventIdentityInOutboxEnvelopeAndRabbitMessage() throws Exception {
        String eventId = UUID.randomUUID().toString();
        publisher.writeToOutbox("payment.settled.v1", "Payment", "501", "corr-1", "cmd-1",
                Map.of("eventId", eventId, "bookingId", 501, "accountId", 7));
        doAnswer(invocation -> {
            CorrelationData confirmation = invocation.getArgument(3);
            confirmation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).convertAndSend(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        publisher.publishPendingEvents();

        var messageCaptor = org.mockito.ArgumentCaptor.forClass(Message.class);
        org.mockito.Mockito.verify(rabbit).convertAndSend(org.mockito.ArgumentMatchers.eq("sgu.events"),
                org.mockito.ArgumentMatchers.eq("payment.settled.v1"), messageCaptor.capture(), any(CorrelationData.class));
        Message message = messageCaptor.getValue();
        JsonNode body = new ObjectMapper().readTree(message.getBody());
        assertEquals(eventId, message.getMessageProperties().getMessageId());
        assertEquals(eventId, body.path("eventId").asText());
        assertEquals("payment.settled.v1", message.getMessageProperties().getType());
        assertEquals("payment.settled.v1", body.path("eventType").asText());
        assertEquals("payment-service", body.path("producer").asText());
        assertEquals("PUBLISHED", jdbc.queryForObject("SELECT status FROM payment_outbox WHERE event_id=?", String.class, eventId));
    }

    @Test
    void doesNotMarkAnUnconfirmedPublishAsPublished() {
        publisher.writeToOutbox("payment.settled.v1", "Payment", "502", null, null,
                Map.of("bookingId", 502));
        doAnswer(invocation -> {
            CorrelationData confirmation = invocation.getArgument(3);
            confirmation.getFuture().complete(new CorrelationData.Confirm(false, "broker rejected"));
            return null;
        }).when(rabbit).convertAndSend(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        publisher.publishPendingEvents();

        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM payment_outbox", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT attempts FROM payment_outbox", Integer.class));
        assertNotNull(jdbc.queryForObject("SELECT last_error FROM payment_outbox", String.class));
    }
}
