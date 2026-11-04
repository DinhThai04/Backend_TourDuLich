package com.example.invoice_service.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** InvoiceAccess composes Vietnamese denial reasons; the customer must actually see them. */
class DenialReasonShapeTest {

    @RestController
    static class ProbeApi {
        @GetMapping("/probe/foreign-invoice")
        String foreignInvoice() { throw new AccessDeniedException("Không được xem hóa đơn của tài khoản khác"); }

        /** What the @PreAuthorize interceptor throws when a permission check simply returns false. */
        @GetMapping("/probe/framework-denial")
        String frameworkDenial() { throw new AccessDeniedException("Access Denied"); }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProbeApi())
            .setControllerAdvice(new GlobalExceptionHandler()).build();

    @Test void composedReasonIsShownToTheUser() throws Exception {
        var body = mvc.perform(get("/probe/foreign-invoice"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED.getCode()))
                .andExpect(jsonPath("$.message").value("Không được xem hóa đơn của tài khoản khác"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        assertFalse(body.contains("Access Denied"), body);
    }

    @Test void frameworkDenialKeepsTheProjectMessage() throws Exception {
        mvc.perform(get("/probe/framework-denial"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED.getCode()))
                .andExpect(result -> assertEquals(ErrorCode.UNAUTHORIZED.getMessage(),
                        com.jayway.jsonpath.JsonPath.read(result.getResponse()
                                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8), "$.message")));
    }
}
