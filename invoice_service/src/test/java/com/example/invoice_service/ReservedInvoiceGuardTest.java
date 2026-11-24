package com.example.invoice_service;

import com.example.invoice_service.dto.request.BookingRequest;
import com.example.invoice_service.dto.request.InvoiceRequest;
import com.example.invoice_service.repository.httpclient.BookingInternalClient;
import com.example.invoice_service.service.InvoiceService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReservedInvoiceGuardTest {
    @Test void reservedBookingCannotEnterLegacyInvoiceOrSeatDecrementPath() {
        var bookingClient = mock(BookingInternalClient.class);
        when(bookingClient.getBookingReservation(42)).thenReturn(Map.of("booking_id", 42, "status", "HELD"));
        var service = new InvoiceService(null, null, null, bookingClient, null);
        var booking = new BookingRequest();
        booking.setBookingId("42");
        var request = new InvoiceRequest();
        request.setBookingRequest(booking);
        var rejection = assertThrows(ResponseStatusException.class, () -> service.createInvoice(request));
        assertEquals(409, rejection.getStatusCode().value());
    }
}
