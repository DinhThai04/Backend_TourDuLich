package com.example.tour_service.service;

import com.example.tour_service.entity.Tour;
import com.example.tour_service.entity.TourDeparture;
import com.example.tour_service.exception.AppException;
import com.example.tour_service.exception.ErrorCode;
import com.example.tour_service.repository.TourDepartureRepository;
import com.example.tour_service.repository.TourRepository;
import com.example.tour_service.repository.httpClient.PricingClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Departure ids are sequential, so an anonymous caller must not be able to enumerate closed slots. */
class DepartureVisibilityTest {
    private final TourDepartureRepository departures = mock(TourDepartureRepository.class);
    private final TourDepartureService service = new TourDepartureService(departures,
            mock(TourRepository.class), mock(PricingClient.class),
            mock(DepartureCapacityReader.class), mock(org.springframework.context.ApplicationEventPublisher.class));

    @AfterEach void clearCaller() { SecurityContextHolder.clearContext(); }

    private static TourDeparture departure(String status, LocalDateTime start) {
        return TourDeparture.builder().id(1).tourCode("SGU-TEST")
                .departureDate(start).returnDate(start.plusDays(3))
                .availableSeats((short) 10).capacity(10)
                .operationalStatus(status).version(0L)
                .tour(Tour.builder().id(7).build()).build();
    }

    private void catalogue(TourDeparture departure) {
        when(departures.findById(1)).thenReturn(Optional.of(departure));
    }

    private static AnonymousAuthenticationToken anonymous() {
        return new AnonymousAuthenticationToken("key", "guest",
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ANONYMOUS")));
    }

    @Test void anonymousCallerReadsAnOpenFutureDeparture() {
        SecurityContextHolder.getContext().setAuthentication(anonymous());
        catalogue(departure("OPEN", LocalDateTime.now().plusDays(1)));
        assertDoesNotThrow(() -> service.getTourDepartureById(1));
    }

    @Test void anonymousCallerCannotReadSuspendedOrEndedDepartures() {
        SecurityContextHolder.getContext().setAuthentication(anonymous());
        for (TourDeparture hidden : List.of(
                departure("SUSPENDED", LocalDateTime.now().plusDays(1)),
                departure("OPEN", LocalDateTime.now().minusDays(1)))) {
            catalogue(hidden);
            assertEquals(ErrorCode.TOUR_DEPARTURE_NOT_FOUND,
                    assertThrows(AppException.class, () -> service.getTourDepartureById(1)).getErrorCode());
        }
    }

    @Test void signedInCallerStillReadsClosedDeparturesOfTheirOwnHistory() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("42", "", List.of()));
        catalogue(departure("CANCELLED", LocalDateTime.now().minusDays(1)));
        assertDoesNotThrow(() -> service.getTourDepartureById(1));
    }
}
