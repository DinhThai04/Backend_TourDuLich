package com.example.ai_service.web;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * ChatErrors only runs once a handler method has matched, so unmapped requests are served by
 * RoutingErrors. What must never reach the caller is Spring's default body, which names the internal
 * servlet path. The 404 arm of the same advice is proven against the running service, because a
 * standalone mock has no resource handler to raise NoResourceFoundException.
 */
class RoutingErrorShapeTest {

    @RestController
    static class ProbeApi {
        @PostMapping(value = "/probe/chat", consumes = MediaType.APPLICATION_JSON_VALUE)
        String chat(@RequestBody String body) { return "ok"; }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProbeApi())
            .setControllerAdvice(new RoutingErrors()).build();

    @Test void wrongVerbKeepsTheAiEnvelopeAndHidesInternalPaths() throws Exception {
        var body = mvc.perform(get("/probe/chat"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value(4050))
                .andExpect(jsonPath("$.error.type").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.message").value("Phương thức không được hỗ trợ cho đường dẫn này."))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertFalse(body.contains("/probe"), "internal path leaked: " + body);
        assertFalse(body.contains("\"path\""), "default Spring body leaked: " + body);
    }
}
