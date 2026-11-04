package com.example.search_service.exception;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** A wrong verb or body type is the caller's mistake and must keep the shared ApiResponse shape. */
class RoutingErrorShapeTest {

    @RestController
    static class ProbeApi {
        @GetMapping("/probe/only-get")
        String onlyGet() { return "ok"; }

        @PostMapping("/probe/json")
        String json(@RequestBody Map<String, String> body) { return "ok"; }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProbeApi())
            .setControllerAdvice(new GlobalExceptionHandler()).build();

    @Test void wrongMethodKeepsProjectCodeInsteadOfSpringErrorBody() throws Exception {
        var response = mvc.perform(post("/probe/only-get"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value(1405))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertTrue(response.contains("Phương thức HTTP"), response);
        assertFalse(response.contains("/probe"), "internal path leaked: " + response);
    }

    @Test void unsupportedBodyTypeKeepsProjectCode() throws Exception {
        mvc.perform(post("/probe/json").contentType(MediaType.IMAGE_PNG).content("abc"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value(1415));
    }
}
