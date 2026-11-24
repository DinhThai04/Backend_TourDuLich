package com.example.booking_service.repository.httpclient;

import com.example.booking_service.config.InvoiceFeignConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@FeignClient(name = "invoice-service-internal", url = "${app.services.invoice-base-url:http://invoice-service:8090/invoice}", configuration = InvoiceFeignConfiguration.class)
public interface InvoiceInternalClient {

    @GetMapping("/internal/invoice/booking/{bookingId}")
    Map<String, Object> getInvoiceByBookingId(@PathVariable("bookingId") Integer bookingId);

    @PostMapping("/internal/invoice/batch")
    List<Map<String, Object>> getInvoicesBatch(@RequestBody List<Integer> bookingIds);
}