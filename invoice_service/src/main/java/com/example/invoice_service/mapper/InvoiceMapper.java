package com.example.invoice_service.mapper;

import com.example.invoice_service.dto.request.InvoiceRequest;
import com.example.invoice_service.dto.response.InvoiceResponse;
import com.example.invoice_service.entity.Invoice;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public interface InvoiceMapper {

    @BeanMapping(ignoreByDefault = true)
    Invoice toInvoice(InvoiceRequest request);

    @Mapping(target = "message", ignore = true)
    @Mapping(target = "totalExtraFee", ignore = true)
    InvoiceResponse toInvoiceResponse(Invoice invoice);

    List<InvoiceResponse> toInvoiceResponseList(List<Invoice> invoices);
}
