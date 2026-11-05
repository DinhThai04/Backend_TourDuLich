package com.example.auth_service;

import com.example.auth_service.exception.GlobalExceptionHandler;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /users/UpdateUser/{id} binds a @ModelAttribute form, so a non-numeric roleId fails as a
 * MethodArgumentNotValidException whose default message is Spring's conversion text naming
 * java.lang.Long. That text must never reach the caller.
 */
class FormBindingErrorHandlingTest {

    @RestController
    static class ProbeApi {
        @PostMapping("/probe/update")
        String update(@jakarta.validation.Valid @ModelAttribute ProbeForm form) {
            return "ok";
        }
    }

    static class ProbeForm {
        Long roleId;

        @Size(max = 20, message = "Tên quá dài")
        String fullName;

        public Long getRoleId() {
            return roleId;
        }

        public void setRoleId(Long roleId) {
            this.roleId = roleId;
        }

        public String getFullName() {
            return fullName;
        }

        public void setFullName(String fullName) {
            this.fullName = fullName;
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProbeApi())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void conversionFailureOnFormBindingIsSanitized() throws Exception {
        mvc.perform(multipart("/probe/update").param("roleId", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1400))
                .andExpect(jsonPath("$.message").value("Tham số không hợp lệ: roleId"))
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    assertFalse(body.contains("Failed to convert"), "conversion text leaked: " + body);
                    assertFalse(body.contains("java."), "internal type name leaked: " + body);
                });
    }

    @Test
    void literalConstraintMessageStillReachesTheUser() throws Exception {
        mvc.perform(multipart("/probe/update")
                .param("fullName", "abcdefghijklmnopqrstuvwxyz")
                .param("roleId", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Tên quá dài"));
    }

    @Test
    void validFormStillResolves() throws Exception {
        mvc.perform(multipart("/probe/update").param("roleId", "7").param("fullName", "Nguyen Van A"))
                .andExpect(status().isOk());
    }
}
