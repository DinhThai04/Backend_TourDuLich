package com.example.invoice_service.controller;

import com.example.invoice_service.dto.request.InvoiceRequest;
import com.example.invoice_service.dto.response.ApiResponse;
import com.example.invoice_service.dto.response.InvoiceResponse;
import com.example.invoice_service.service.InvoiceService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/direct")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class InvoiceController {
    private final InvoiceService invoiceService;

    @GetMapping("/{id}")
    public ApiResponse<InvoiceResponse> getInvoiceById(@PathVariable Long id) {
        return ApiResponse.<InvoiceResponse>builder()
                .result(invoiceService.getInvoiceById(id))
                .message("Got successfully")
                .build();
    }

    @GetMapping("/booking/{id}")
    public ApiResponse<InvoiceResponse> getInvoiceByBookingId(@PathVariable Long id) {
        return ApiResponse.<InvoiceResponse>builder()
                .result(invoiceService.getInvoiceByBookingId(id))
                .message("Got successfully")
                .build();
    }

    @GetMapping("/all")
    public ApiResponse<List<InvoiceResponse>> getAllInvoices(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false, defaultValue = "false") boolean mine) {
        return ApiResponse.<List<InvoiceResponse>>builder()
                .result(invoiceService.getAllInvoices(page, size, mine))
                .message("Got successfully")
                .build();
    }

    /**
     * Legacy cash-desk invoice creation. Customers must pay through the booking VNPay flow,
     * where the invoice is written by the server after a verified transaction; otherwise a
     * customer could mark their own booking as PAID without transferring any money.
     */
    @PostMapping("/create")
    @PreAuthorize("hasAnyAuthority('SGU_SUPER_ADMIN', 'PERM:INVOICES_CREATE')")
    public ApiResponse<InvoiceResponse> createPaidInvoice(@jakarta.validation.Valid @RequestBody InvoiceRequest request) {
        return ApiResponse.<InvoiceResponse>builder()
                .result(invoiceService.payAndCreateInvoice(request))
                .message("Created successfully")
                .build();
    }
}
