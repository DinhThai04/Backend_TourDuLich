package com.example.booking_service.reservation;

import com.example.booking_service.controller.InternalBookingController;
import com.example.booking_service.exception.GlobalExceptionHandler;
import com.example.booking_service.repository.httpclient.AuthInternalClient;
import com.example.booking_service.repository.httpclient.InvoiceInternalClient;
import com.example.booking_service.repository.httpclient.PaymentInternalClient;
import com.example.booking_service.repository.httpclient.TourInternalClient;
import com.example.booking_service.service.InternalBookingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InternalBookingMissingRowTest {
    private MockMvc mvc;
    private TourInternalClient tours;

    @BeforeEach
    void setUp() throws Exception {
        var database = new LifecycleTestDatabase();
        tours = mock(TourInternalClient.class);
        var service = new InternalBookingService(database.jdbc, mock(BookingSettlementService.class), tours,
                mock(AuthInternalClient.class), mock(PaymentInternalClient.class), mock(InvoiceInternalClient.class));
        mvc = MockMvcBuilders.standaloneSetup(new InternalBookingController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void missingBookingReturns404() throws Exception {
        mvc.perform(get("/internal/booking/bookings/99999")).andExpect(status().isNotFound());
    }

    @Test
    void missingBookingOwnerReturns404() throws Exception {
        mvc.perform(get("/internal/booking/bookings/99999/owner")).andExpect(status().isNotFound());
    }

    @Test
    void missingReservationReturns404() throws Exception {
        mvc.perform(get("/internal/booking/bookings/99999/reservation")).andExpect(status().isNotFound());
    }

    @Test
    void missingBookingDoesNotCallTourToHoldSeats() throws Exception {
        mvc.perform(post("/internal/booking/bookings/99999/decrement-seats")
                .contentType(MediaType.APPLICATION_JSON).content("{\"seats\":1}"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(tours);
    }
}
