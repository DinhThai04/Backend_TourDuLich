package com.example.invoice_service;

import com.example.invoice_service.config.InvoiceAccess;
import com.example.invoice_service.entity.Invoice;
import com.example.invoice_service.mapper.InvoiceMapper;
import com.example.invoice_service.repository.InvoiceRepository;
import com.example.invoice_service.service.InvoiceService;
import org.junit.jupiter.api.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InvoiceOwnershipTest {
    private final InvoiceRepository invoices = mock(InvoiceRepository.class);
    private final InvoiceMapper mapper = mock(InvoiceMapper.class);
    private final InvoiceService service = new InvoiceService(null, invoices, mapper, null, null);
    @AfterEach void clean() { SecurityContextHolder.clearContext(); }
    private void login(String id, String... permissions) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(id, "",
                Arrays.stream(permissions).map(SimpleGrantedAuthority::new).toList()));
    }
    @Test void customerCannotReadAnotherInvoiceByEitherIdentifier() {
        login("2");
        var other = Invoice.builder().id(10).accountId(3).bookingId(4).build();
        when(invoices.findById(10)).thenReturn(Optional.of(other));
        when(invoices.findByBookingId(4)).thenReturn(Optional.of(other));
        assertThrows(AccessDeniedException.class, () -> service.getInvoiceById(10L));
        assertThrows(AccessDeniedException.class, () -> service.getInvoiceByBookingId(4L));
        verifyNoInteractions(mapper);
    }
    @Test void customerListIsScopedEvenWithInvoiceListPermission() {
        login("2", "INVOICES_GET_ALL");
        when(invoices.findByAccountId(eq(2), any(Pageable.class))).thenReturn(Page.<Invoice>empty());
        service.getAllInvoices(null, null, false);
        verify(invoices).findByAccountId(eq(2), any(Pageable.class));
        verify(invoices, never()).findAll(any(Pageable.class));
    }
    /** The profile history page is self-service, so even a super admin must get only their own rows. */
    @Test void ownInvoicesOverrideTheAdminList() {
        login("2", "SGU_SUPER_ADMIN");
        when(invoices.findByAccountId(eq(2), any(Pageable.class))).thenReturn(Page.<Invoice>empty());
        service.getAllInvoices(null, null, true);
        verify(invoices).findByAccountId(eq(2), any(Pageable.class));
        verify(invoices, never()).findAll(any(Pageable.class));
    }
    @Test void cashDeskPermissionMayBillAnotherOwnersBooking() {
        login("5", "PERM:INVOICES_CREATE");
        assertDoesNotThrow(() -> InvoiceAccess.requireCreateOwner(3));
        login("2");
        assertThrows(AccessDeniedException.class, () -> InvoiceAccess.requireCreateOwner(3));
    }
    @Test void ownerAndAdminCanReadInvoices() {
        var own = Invoice.builder().id(10).accountId(2).build();
        when(invoices.findById(10)).thenReturn(Optional.of(own));
        when(invoices.findAll(any(Pageable.class))).thenReturn(Page.<Invoice>empty());
        login("2"); service.getInvoiceById(10L);
        login("1", "SGU_SUPER_ADMIN"); service.getInvoiceById(10L); service.getAllInvoices(null, null, false);
        verify(mapper, times(2)).toInvoiceResponse(own); verify(invoices).findAll(any(Pageable.class));
    }
}
