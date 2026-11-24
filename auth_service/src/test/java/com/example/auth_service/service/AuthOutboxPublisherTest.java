package com.example.auth_service.service;

import com.example.auth_service.repository.httpclient.FileInternalClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class AuthOutboxPublisherTest {
    private JdbcTemplate jdbc;
    private FileInternalClient fileClient;
    private AuthOutboxPublisher publisher;

    @BeforeEach void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:auth_outbox_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("""
                CREATE TABLE auth_outbox(
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, event_id VARCHAR(36) NOT NULL,
                  event_type VARCHAR(120) NOT NULL, aggregate_id VARCHAR(80) NOT NULL,
                  status VARCHAR(20) NOT NULL, attempts INT NOT NULL, available_at TIMESTAMP NOT NULL,
                  published_at TIMESTAMP NULL, last_error VARCHAR(1000), created_at TIMESTAMP NOT NULL)
                """);
        fileClient = mock(FileInternalClient.class);
        publisher = new AuthOutboxPublisher(jdbc, fileClient);
    }

    @Test void acknowledgedFileDetachMarksEventPublished() {
        String eventId = insertEvent(0);
        publisher.publishPendingEvents();

        verify(fileClient).detachAccountMedia("41", eventId);
        assertEquals("PUBLISHED", status());
        assertEquals(1, attempts());
    }

    @Test void transientFailureSchedulesRetryAndStoresOnlySafeErrorName() {
        String eventId = insertEvent(0);
        doThrow(new IllegalStateException("sensitive remote response"))
                .when(fileClient).detachAccountMedia("41", eventId);
        publisher.publishPendingEvents();

        assertEquals("PENDING", status());
        assertEquals("IllegalStateException", jdbc.queryForObject("SELECT last_error FROM auth_outbox", String.class));
        assertEquals(1, attempts());
    }

    @Test void exhaustedDeliveryMovesEventToDeadLetter() {
        insertEvent(11);
        doThrow(new IllegalStateException("unavailable")).when(fileClient).detachAccountMedia(eq("41"), anyString());
        publisher.publishPendingEvents();

        assertEquals("DEAD_LETTER", status());
        assertEquals(12, attempts());
    }

    private String insertEvent(int attempts) {
        String eventId = UUID.randomUUID().toString();
        Timestamp now = Timestamp.valueOf(LocalDateTime.now().minusSeconds(1));
        jdbc.update("""
                INSERT INTO auth_outbox(event_id,event_type,aggregate_id,status,attempts,available_at,created_at)
                VALUES (?,'auth.account.deleted.v1','41','PENDING',?,?,?)
                """, eventId, attempts, now, now);
        return eventId;
    }

    private String status() { return jdbc.queryForObject("SELECT status FROM auth_outbox", String.class); }
    private int attempts() { return jdbc.queryForObject("SELECT attempts FROM auth_outbox", Integer.class); }
}
