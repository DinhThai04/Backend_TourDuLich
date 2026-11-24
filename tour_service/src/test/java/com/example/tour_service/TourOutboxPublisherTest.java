package com.example.tour_service;

import com.example.tour_service.repository.httpclient.FileInternalClient;
import com.example.tour_service.service.TourOutboxPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;

class TourOutboxPublisherTest {
    @Test
    void claimsAndDeliversGalleryDetachUsingDurableEventId() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        FileInternalClient fileClient = mock(FileInternalClient.class);
        LocalDateTime availableAt = LocalDateTime.now().minusMinutes(1);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(java.util.List.of(Map.of(
                "id", 42L,
                "event_id", "event-42",
                "aggregate_id", "123",
                "status", "PENDING",
                "available_at", Timestamp.valueOf(availableAt))));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1, 1);
        when(jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", LocalDateTime.class))
                .thenReturn(availableAt.plusMinutes(1));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(1);

        new TourOutboxPublisher(jdbc, fileClient).publishPendingEvents();

        verify(fileClient).detachTourGallery("123", "event-42");
        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("status = 'PUBLISHED'"), eq(42L));
    }

    @Test
    void failedDeliveryReturnsToPendingWithSanitizedRetryMetadata() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        FileInternalClient fileClient = mock(FileInternalClient.class);
        LocalDateTime availableAt = LocalDateTime.now().minusMinutes(1);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(java.util.List.of(Map.of(
                "id", 43L,
                "event_id", "event-43",
                "aggregate_id", "124",
                "status", "PENDING",
                "available_at", Timestamp.valueOf(availableAt))));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1, 1);
        when(jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", LocalDateTime.class))
                .thenReturn(availableAt.plusMinutes(1));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(1);
        doThrow(new IllegalStateException("sensitive remote response must not reach outbox"))
                .when(fileClient).detachTourGallery("124", "event-43");

        new TourOutboxPublisher(jdbc, fileClient).publishPendingEvents();

        var updates = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, times(2)).update(anyString(), updates.capture());
        Object[] retryUpdate = updates.getAllValues().get(1);
        assertEquals("PENDING", retryUpdate[0]);
        assertEquals("IllegalStateException", retryUpdate[2]);
    }
}
