package com.example.booking_service.advice;

import com.example.booking_service.config.BookingMailOutboxPublisher;
import com.example.booking_service.dto.request.BookingRequest;
import com.example.booking_service.dto.request.CustomerRequest;
import com.example.booking_service.reservation.BookingLifecycleService;
import com.example.booking_service.reservation.BookingMailEvents;
import com.example.booking_service.reservation.BookingAccess;
import com.example.booking_service.reservation.LifecycleTestDatabase;
import com.example.booking_service.reservation.ReservationExpiryService;
import com.example.booking_service.reservation.ReservationPolicy;
import com.example.booking_service.repository.httpclient.AuthInternalClient;
import com.example.booking_service.repository.httpclient.InvoiceInternalClient;
import com.example.booking_service.repository.httpclient.PricingInternalClient;
import com.example.booking_service.repository.httpclient.TourInternalClient;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BookingAdviceServiceTest {
    LifecycleTestDatabase db;
    LifecycleTestDatabase.MutableClock clock;
    BookingAdviceService service;
    BookingLifecycleService lifecycle;
    BookingMailOutboxPublisher publisher;
    AuthInternalClient authClient;
    TourInternalClient tourClient;
    PricingInternalClient pricingClient;
    InvoiceInternalClient invoiceClient;
    @BeforeEach void setup() throws Exception {
        db = new LifecycleTestDatabase();
        clock = new LifecycleTestDatabase.MutableClock(LocalDateTime.of(2026, 11, 1, 10, 0));
        authClient = mock(AuthInternalClient.class);
        tourClient = mock(TourInternalClient.class);
        invoiceClient = mock(InvoiceInternalClient.class);
        pricingClient = mock(PricingInternalClient.class);
        when(authClient.getUserById(anyLong())).thenReturn(Map.of("email", "tester@example.com", "username", "tester"));
        when(authClient.getUserStatus(anyLong())).thenReturn(Map.of("status", "ACTIVE"));
        when(pricingClient.getPriceById(anyLong())).thenReturn(Map.of("id", 1, "adultPrice", 3350000, "childPrice", 2000000, "toddlerPrice", 0, "infantPrice", 0, "singleSupplementPrice", 500));
        when(invoiceClient.getInvoiceByBookingId(anyInt())).thenReturn(Map.of("status", "PAID"));
        answerDeparture(tourClient);
        publisher = mock(BookingMailOutboxPublisher.class);
        var events = new BookingMailEvents(db.jdbc, publisher, clock, authClient, tourClient);
        var access = mock(BookingAccess.class); when(access.accountId()).thenReturn(7);
        var payClient = com.example.booking_service.TestInternalClients.payment(db.jdbc);
        var invClient = com.example.booking_service.TestInternalClients.invoice(db.jdbc);
        var tClient = com.example.booking_service.TestInternalClients.tour(db.jdbc);
        var expiry = new ReservationExpiryService(db.jdbc, db.manager, events, clock, tClient, payClient, invClient);
        lifecycle = new BookingLifecycleService(db.jdbc, db.manager, new ReservationPolicy(), expiry, events, access, clock,
                authClient, invClient, pricingClient, tClient);
        var access2 = mock(BookingAccess.class); when(access2.accountId()).thenReturn(7);
        service = new BookingAdviceService(db.jdbc, access2, clock, tClient, payClient, invClient);
    }
    BookingRequest request() {
        return BookingRequest.builder().userId("7").tourDepartureId("1").listOfCustomers(List.of(
                CustomerRequest.builder().fullName("Danh").birthdate("2000-01-01").gender("Male").address("12 Đường Lê Lợi, Phường Ba Đình, Thành phố Hà Nội").build())).build();
    }
    int create() { return Integer.parseInt(lifecycle.create(request()).getId()); }
    void answerDeparture(TourInternalClient tourClient) {
        when(tourClient.getDeparture(anyInt())).thenAnswer(inv -> {
            int id = inv.getArgument(0);
            var row = db.jdbc.queryForMap("SELECT * FROM tour_departure WHERE id = ?", id);
            var map = new java.util.LinkedHashMap<String, Object>();
            for (var e : row.entrySet()) map.put(e.getKey().toLowerCase(), e.getValue());
            return map;
        });
    }
    @Test void confirmCreatesBookingAndMailEvent() {
        var input = request();
        input.setClientKey(UUID.randomUUID().toString());
        var created = lifecycle.create(input);
        assertEquals("HELD", lifecycle.get(Integer.parseInt(created.getId())).getReservationStatus());
        verify(publisher, times(1)).sendNow(anyString());
    }
}