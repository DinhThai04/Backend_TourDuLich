package com.example.invoice_service.controller;

import com.example.invoice_service.client.PaymentClient;
import com.example.invoice_service.dto.request.InvoiceRequest;
import com.example.invoice_service.dto.response.ApiResponse;
import com.example.invoice_service.dto.response.BookingResponse;
import com.example.invoice_service.dto.response.InvoiceResponse;
import com.example.invoice_service.dto.response.MoMoResponse;
import com.example.invoice_service.service.InvoiceService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/direct")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)

public class InvoiceController {
    private final InvoiceService invoiceService;
    private final PaymentClient paymentClient;

    /***
     *
     * @param id
     * @return
     */
    @GetMapping("/{id}")
    public ApiResponse<InvoiceResponse> getInvoiceById(@PathVariable Long id) {
        return ApiResponse.<InvoiceResponse>builder()
                .result(invoiceService.getInvoiceById(id))
                .message("Got successfully")
                .build();
    }

    /***
     *
     * @param id
     * @return
     */
    @GetMapping("/booking/{id}")
    public ApiResponse<InvoiceResponse> getInvoiceByBookingId(@PathVariable Long id) {
        return ApiResponse.<InvoiceResponse>builder()
                .result(invoiceService.getInvoiceByBookingId(id))
                .message("Got successfully")
                .build();
    }

    /***
     *
     * @return
     */
    @GetMapping("/all")
    public ApiResponse<List<InvoiceResponse>> getAllInvoices() {
        return ApiResponse.<List<InvoiceResponse>>builder()
                .result(invoiceService.getAllInvoices())
                .message("Got successfully")
                .build();
    }

    /***
     *
     * @param request
     * @return
     */
    @PostMapping("/create")
    public ApiResponse<InvoiceResponse> payAndCreteInvoice(@RequestBody InvoiceRequest request){
            return ApiResponse.<InvoiceResponse>builder()
                .result(invoiceService.payAndCreateInvoice(request))
                .message("Created successfully")
                .build();
    }


//    @PostMapping("/create")
//    public ApiResponse<InvoiceResponse> createMomoQR(@RequestBody InvoiceRequest request){
//        try {
//            return ApiResponse.<InvoiceResponse>builder()
//                .result(invoiceService.payAndCreateInvoice(request))
//                .message("Created successfully")
//                .build();
//        } catch (Exception ex) {
//            System.err.println("Failed to create MoMo payment: " + ex.getMessage());
//            return null;
//        }
//    }

}
