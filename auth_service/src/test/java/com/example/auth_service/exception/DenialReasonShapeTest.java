package com.example.auth_service.exception;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** A denial must reach the customer as the reason the service composed, never as a blank 403. */
class DenialReasonShapeTest {

    @RestController
    static class ProbeApi {
        @GetMapping("/probe/role-guard")
        String roleGuard() { throw new AccessDeniedException("Nhân viên chỉ được cập nhật tài khoản khách hàng"); }

        /** What the @PreAuthorize interceptor throws when a permission check simply returns false. */
        @GetMapping("/probe/framework-denial")
        String frameworkDenial() { throw new AccessDeniedException("Access Denied"); }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProbeApi())
            .setControllerAdvice(new GlobalExceptionHandler()).build();

    @Test void composedReasonIsShownToTheUser() throws Exception {
        var body = mvc.perform(get("/probe/role-guard"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED.getCode()))
                .andExpect(jsonPath("$.message")
                        .value("Nhân viên chỉ được cập nhật tài khoản khách hàng"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertFalse(body.contains("Access Denied"), body);
    }

    @Test void frameworkDenialKeepsTheProjectMessage() throws Exception {
        mvc.perform(get("/probe/framework-denial"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED.getCode()))
                .andExpect(result -> assertEquals(ErrorCode.UNAUTHORIZED.getMessage(),
                        com.jayway.jsonpath.JsonPath.read(
                                result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.message")))
                .andExpect(result -> assertTrue(
                        result.getResponse().getContentAsString(StandardCharsets.UTF_8).contains("quyền")));
    }
}
