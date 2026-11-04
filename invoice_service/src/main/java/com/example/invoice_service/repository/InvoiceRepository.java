package com.example.invoice_service.repository;

import com.example.invoice_service.entity.Invoice;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface InvoiceRepository extends JpaRepository<Invoice, Integer> {

    Optional<Invoice> findByBookingId(int bookingId);

    Page<Invoice> findByAccountId(int accountId, Pageable pageable);
}
