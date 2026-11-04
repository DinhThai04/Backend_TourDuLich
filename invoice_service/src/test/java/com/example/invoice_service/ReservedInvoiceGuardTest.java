package com.example.invoice_service;

import com.example.invoice_service.dto.request.BookingRequest;
import com.example.invoice_service.dto.request.InvoiceRequest;
import com.example.invoice_service.service.InvoiceService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReservedInvoiceGuardTest {
    @Test void reservedBookingCannotEnterLegacyInvoiceOrSeatDecrementPath() {
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(),eq(Integer.class),eq(42))).thenReturn(1);
        var service=new InvoiceService(jdbc,null,null,null,null);
        var booking=new BookingRequest();booking.setBookingId("42");
        var request=new InvoiceRequest();request.setBookingRequest(booking);
        var rejection=assertThrows(ResponseStatusException.class,()->service.createInvoice(request));
        assertEquals(409,rejection.getStatusCode().value());
    }
}
