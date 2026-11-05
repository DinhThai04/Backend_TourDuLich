package com.example.tour_service.config;

import com.example.tour_service.dto.request.TourDepartureRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;

class DepartureTimeTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    @Test void utcOffsetAndVietnamWallTimeAllRepresentTheSameDeparture() throws Exception {
        for (String input : new String[]{"2026-11-20T01:00:00.000Z", "2026-11-20T08:00:00+07:00", "2026-11-20T08:00:00"}) {
            var request = json.readValue("{\"departureDate\":\"" + input + "\",\"returnDate\":\"" + input + "\"}", TourDepartureRequest.class);
            assertEquals(LocalDateTime.of(2026, 11, 20, 8, 0), request.getDepartureDate());
            assertEquals(request.getDepartureDate(), request.getReturnDate());
        }
    }
    @Test void offsetConversionCanCrossTheCalendarDay() throws Exception {
        var request = json.readValue("{\"departureDate\":\"2026-11-19T18:00:00Z\"}", TourDepartureRequest.class);
        assertEquals(LocalDateTime.of(2026,11,20,1,0), request.getDepartureDate());
    }
    @Test void invalidDatesAreRejectedRatherThanSilentlyAdjusted() {
        assertThrows(Exception.class, () -> json.readValue("{\"departureDate\":\"2026-02-30T08:00:00\"}", TourDepartureRequest.class));
    }
}
