package com.example.booking_service.reservation;

import com.example.booking_service.repository.httpclient.TourInternalClient;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BookingSettlementServiceTest {
    @Test
    void retriesConfirmationWithoutWritingBookingBeforeTourAcknowledges() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TourInternalClient tours = mock(TourInternalClient.class);
        when(jdbc.queryForList(startsWith("SELECT status,tour_departure_id"), eq(50)))
                .thenReturn(List.of(Map.of("status", "PENDING_PAYMENT", "tour_departure_id", 60)));
        when(jdbc.queryForList(startsWith("SELECT departure_id,status"), eq(50)))
                .thenReturn(List.of(Map.of("status", "HELD", "departure_id", 60)));
        doThrow(new IllegalStateException("Tour chưa phản hồi")).doNothing().when(tours).confirmHold(60, 50);

        var service = new BookingSettlementService(jdbc, tours, Clock.systemUTC());
        assertThrows(IllegalStateException.class, () -> service.confirmPaid(50));
        verify(jdbc, never()).update(startsWith("UPDATE booking SET"), eq(50));

        service.confirmPaid(50);
        verify(tours, times(2)).confirmHold(60, 50);
        verify(jdbc).update(startsWith("UPDATE booking SET"), eq(50));
    }
}
