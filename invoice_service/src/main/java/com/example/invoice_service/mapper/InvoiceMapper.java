package com.example.invoice_service.mapper;

import com.example.invoice_service.dto.request.InvoiceRequest;
import com.example.invoice_service.dto.response.InvoiceResponse;
import com.example.invoice_service.entity.Invoice;
import org.mapstruct.Mapper;
import org.springframework.web.bind.annotation.Mapping;

@Mapper(componentModel = "spring")

public interface InvoiceMapper {
    // DTO -> Entity
    Invoice toInvoice(InvoiceRequest request);

    // Entity -> DTO
    InvoiceResponse toInvoiceResponse(Invoice invoice);

    // List Entity -> List DTO
    java.util.List<InvoiceResponse> toInvoiceResponseList(java.util.List<Invoice> invoices);
}
