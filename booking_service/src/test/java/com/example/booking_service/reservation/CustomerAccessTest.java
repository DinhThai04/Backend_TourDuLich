package com.example.booking_service.reservation;

import com.example.booking_service.entity.*;
import com.example.booking_service.mapper.CustomerMapper;
import com.example.booking_service.repository.*;
import com.example.booking_service.service.CustomerService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CustomerAccessTest {
    private final CustomerRepository customers = mock(CustomerRepository.class);
    private final BookingRepository bookings = mock(BookingRepository.class);
    private final BookingAccess access = mock(BookingAccess.class);
    private final CustomerService service = new CustomerService(customers, mock(CustomerMapper.class), bookings, access);
    @Test void listIsRestrictedToCurrentAccount() {
        when(access.accountId()).thenReturn(2);
        when(customers.findByBookingAccountId(eq(2), any(Pageable.class))).thenReturn(Page.empty());
        service.getAllCustomers(null, null, false);
        verify(customers).findByBookingAccountId(eq(2), any(Pageable.class));
        verify(customers, never()).findAllWithBooking(any(Pageable.class));
    }
    /** The profile history page is self-service, so a staff account must not get the global page. */
    @Test void ownPassengersOverridesTheGlobalListPermission() {
        when(access.accountId()).thenReturn(2);
        when(access.can("BOOKINGS_GET_ALL")).thenReturn(true);
        when(customers.findByBookingAccountId(eq(2), any(Pageable.class))).thenReturn(Page.empty());
        service.getAllCustomers(null, null, true);
        verify(customers).findByBookingAccountId(eq(2), any(Pageable.class));
        verify(customers, never()).findAllWithBooking(any(Pageable.class));
    }
    @Test void bookingAndPassengerDetailRequireOwnership() {
        var booking = Booking.builder().id(4).accountId(3).build();
        when(bookings.findById(4)).thenReturn(Optional.of(booking));
        when(customers.findById(5)).thenReturn(Optional.of(Customer.builder().id(5).booking(booking).build()));
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN)).when(access).requireReadOwner(3,"CUSTOMERS_GET_ALL");
        assertThrows(ResponseStatusException.class, () -> service.getListOfCustomersByBookingId(4L));
        assertThrows(ResponseStatusException.class, () -> service.getCustomerById(5L));
        verify(customers, never()).findCustomersByBookingId(any());
    }
    @Test void standalonePassengerCreationCannotBypassSeatReservation() {
        assertThrows(ResponseStatusException.class, () -> service.createCustomers(List.of()));
        verify(customers, never()).saveAll(any());
    }
    @Test void bookingReadPermissionIncludesPassengersWithoutGrantingCancellation() {
        when(access.can("BOOKINGS_GET_ALL")).thenReturn(true);
        when(customers.findAllWithBooking(any(Pageable.class))).thenReturn(Page.empty());
        var booking=Booking.builder().id(4).accountId(3).build();when(bookings.findById(4)).thenReturn(Optional.of(booking));
        service.getAllCustomers(null, null, false);service.getListOfCustomersByBookingId(4L);
        verify(customers).findAllWithBooking(any(Pageable.class));verify(customers).findCustomersByBookingId(4L);
        verify(access,never()).requireReadOwner(anyInt(),anyString());
    }
}
