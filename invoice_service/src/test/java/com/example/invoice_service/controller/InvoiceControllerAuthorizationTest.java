package com.example.invoice_service.controller;

import com.example.invoice_service.dto.response.InvoiceResponse;
import com.example.invoice_service.service.InvoiceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = InvoiceController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(InvoiceControllerAuthorizationTest.MethodSecurityConfig.class)
class InvoiceControllerAuthorizationTest {

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityConfig {
    }

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private InvoiceService invoiceService;

    private static final String VALID_BODY = """
            {"bookingRequest":{"bookingId":"1","userId":"7","tourDepartureId":"1",
              "listOfCustomers":[{"fullName":"Nguyen Van A"}]}}
            """;

    @Test
    @WithMockUser(authorities = "ROLE_CUSTOMER")
    void customerCannotCreatePaidInvoiceDirectly() throws Exception {
        mockMvc.perform(post("/direct/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isForbidden());

        verifyNoInteractions(invoiceService);
    }

    @Test
    @WithMockUser(authorities = "PERM:INVOICES_CREATE")
    void rejectsIncompleteBookingRequest() throws Exception {
        mockMvc.perform(post("/direct/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookingRequest\":{}}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(invoiceService);
    }

    @Test
    @WithMockUser(authorities = "PERM:INVOICES_CREATE")
    void staffWithCashDeskPermissionCanCreateInvoice() throws Exception {
        when(invoiceService.payAndCreateInvoice(any()))
                .thenReturn(InvoiceResponse.builder().id("1").status("PAID").build());

        mockMvc.perform(post("/direct/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.id").value("1"));
    }
}
