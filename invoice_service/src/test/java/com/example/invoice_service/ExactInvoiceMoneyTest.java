package com.example.invoice_service;

import com.example.invoice_service.entity.Invoice;
import com.example.invoice_service.mapper.InvoiceMapper;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExactInvoiceMoneyTest {

    @Test
    void largeOddVndAmountsRemainExactThroughEntityAndApiMapping() {
        var amount = new BigDecimal("280174059");
        var invoice = Invoice.builder().id(1).totalBookingTourExpense(amount).build();

        assertEquals(amount, invoice.getTotalBookingTourExpense());
        assertEquals("280174059",
                Mappers.getMapper(InvoiceMapper.class).toInvoiceResponse(invoice).getTotalBookingTourExpense());
    }
}
