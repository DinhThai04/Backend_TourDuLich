package com.example.notification_service;

import com.example.notification_service.config.MailProperties;
import com.example.notification_service.domain.MailPayload;
import com.example.notification_service.repository.MailOutboxRepository;
import com.example.notification_service.service.MailDeliveryWorker;
import com.example.notification_service.service.MailEnqueueService;
import com.example.notification_service.dto.MailEnqueueRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NotificationApiTest {

    private static final String KEY = "0123456789abcdef0123456789abcdef";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MailOutboxRepository repository;

    @Autowired
    private MailEnqueueService enqueueService;

    @Autowired
    private ObjectMapper mapper;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM mail_outbox");
    }

    @Test
    void internalApiRequiresKey() throws Exception {
        mvc.perform(get("/internal/notifications/mail/bookings/100"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void enqueueIsIdempotentAndListedByBooking() throws Exception {
        String body = """
                {"eventKey":"booking:100:created","eventType":"BOOKING_CREATED",
                 "sourceService":"booking-service","bookingId":100,"accountId":7,
                 "recipient":"user@example.com","subject":"Booking created","html":"<p>OK</p>",
                 "text":"OK","scheduledAt":"2026-11-20T10:00:00"}
                """;
        mvc.perform(post("/internal/notifications/mail")
                        .header("X-Internal-Notification-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.created").value(true));

        mvc.perform(post("/internal/notifications/mail")
                        .header("X-Internal-Notification-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.created").value(false));

        mvc.perform(get("/internal/notifications/mail/bookings/100")
                        .header("X-Internal-Notification-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].event_type").value("BOOKING_CREATED"))
                .andExpect(jsonPath("$[0].status").value("PENDING"));
    }

    @Test
    void failedMailCanBeRetried() throws Exception {
        enqueueService.enqueue(request("booking:100:failed"));
        jdbc.update("UPDATE mail_outbox SET status='FAILED',last_error='boom'");

        mvc.perform(post("/internal/notifications/mail/bookings/100/"
                        + jdbc.queryForObject("SELECT id FROM mail_outbox", Long.class) + "/retry")
                        .header("X-Internal-Notification-Key", KEY))
                .andExpect(status().isNoContent());

        assertEquals("RETRY", jdbc.queryForObject("SELECT status FROM mail_outbox", String.class));
    }

    @Test
    void workerClaimsAndSendsMail() {
        enqueueService.enqueue(request("booking:100:sent"));
        AtomicReference<String> recipient = new AtomicReference<>();
        AtomicReference<MailPayload> payload = new AtomicReference<>();
        MailProperties properties = new MailProperties();
        properties.setEnabled(true);
        properties.setTestMode(false);
        properties.setFrom("no-reply@sgu-tour.example");
        MailDeliveryWorker worker = new MailDeliveryWorker(repository,
                (to, mail) -> {
                    recipient.set(to);
                    payload.set(mail);
                }, properties, mapper, Clock.system(ZoneId.of("Asia/Ho_Chi_Minh")));

        worker.poll();

        assertEquals("user@example.com", recipient.get());
        assertEquals("Booking created", payload.get().subject());
        assertEquals("SENT", jdbc.queryForObject("SELECT status FROM mail_outbox", String.class));
        assertTrue(jdbc.queryForObject("SELECT sent_at IS NOT NULL FROM mail_outbox", Boolean.class));
    }

    private MailEnqueueRequest request(String key) {
        return new MailEnqueueRequest(key, "BOOKING_CREATED", "booking-service", 100, 7,
                "user@example.com", "Booking created", "<p>OK</p>", "OK",
                LocalDateTime.now().minusSeconds(1));
    }
}
