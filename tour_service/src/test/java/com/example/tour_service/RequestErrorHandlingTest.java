package com.example.tour_service;

import com.example.tour_service.exception.GlobalExceptionHandler;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Malformed query/form input must answer 400 with the shared error envelope, never a 500. */
class RequestErrorHandlingTest {

    @RestController
    static class TestApi {
        @GetMapping("/probe/int")
        String intParam(@RequestParam int value) {
            return "ok";
        }

        @GetMapping("/probe/required")
        String required(@RequestParam String value) {
            return "ok";
        }

        @GetMapping("/probe/form")
        String form(@ModelAttribute Form form) {
            return "ok";
        }

        @GetMapping("/probe/validated-form")
        String validatedForm(@Valid @ModelAttribute Form form) {
            return "ok";
        }

        @PostMapping("/probe/json")
        String json(@RequestBody Map<String, Integer> body) {
            return "ok";
        }

        static class Form {
            @Positive(message = "Số phải lớn hơn 0")
            int number;

            public int getNumber() {
                return number;
            }

            public void setNumber(int number) {
                this.number = number;
            }
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new TestApi())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void malformedOrMissingQueryParametersReturnBadRequest() throws Exception {
        mvc.perform(get("/probe/int").param("value", "abc"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1400));
        mvc.perform(get("/probe/required"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1400));
        mvc.perform(get("/probe/form").param("number", "abc"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1400))
                // The raw Spring conversion text names java.math/java.time types and annotation
                // class names, so /tours/search must never echo it back to an anonymous caller.
                .andExpect(jsonPath("$.message").value("Tham số không hợp lệ: number"))
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    assertFalse(body.contains("Failed to convert"), "conversion text leaked: " + body);
                    assertFalse(body.contains("java."), "internal type name leaked: " + body);
                });
    }

    @Test
    void literalConstraintMessagesStayVisibleToTheUser() throws Exception {
        mvc.perform(get("/probe/validated-form").param("number", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Số phải lớn hơn 0"));
    }

    @Test
    void unreadableBodyReturnsBadRequest() throws Exception {
        mvc.perform(post("/probe/json").contentType("application/json").content("{\"a\":\"not-a-number\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1400));
    }
}
