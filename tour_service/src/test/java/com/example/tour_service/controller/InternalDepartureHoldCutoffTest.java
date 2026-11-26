package com.example.tour_service.controller;

import com.example.tour_service.advice.AdviceLocations;
import com.example.tour_service.dto.internal.HoldSeatsRequest;
import com.example.tour_service.entity.TourDeparture;
import com.example.tour_service.repository.TourDepartureRepository;
import com.example.tour_service.service.DepartureCapacityReader;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class InternalDepartureHoldCutoffTest {
    @Test
    void refusesNewHoldWhenCutoffPassedBeforeAcquiringDepartureLock() {
        var repository = mock(TourDepartureRepository.class);
        var capacity = mock(DepartureCapacityReader.class);
        var jdbc = mock(JdbcTemplate.class);
        var controller = new InternalTourDepartureController(repository, capacity, jdbc, mock(AdviceLocations.class));
        var departure = new TourDeparture();
        var now = LocalDateTime.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        departure.setDepartureDate(now.plusHours(2));
        when(repository.lockById(42)).thenReturn(Optional.of(departure));

        var request = HoldSeatsRequest.builder().seats(1).pickupConfirmationKey("").expiresAt(now.plusMinutes(10)).build();
        assertEquals(409, controller.holdSeats(42, request).getStatusCode().value());
        verify(repository).lockById(42);
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(capacity, jdbc);
    }
}
