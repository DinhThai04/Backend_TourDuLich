package com.example.search_service.config;

import java.nio.charset.StandardCharsets;

import com.example.search_service.exception.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthenticationErrorEncodingTest {

    @Test
    void writesVietnameseUnauthenticatedMessageAsUtf8Json() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        new JwtAuthenticationEntryPoint()
                .commence(request, response, new BadCredentialsException("bad credentials"));

        assertEquals(401, response.getStatus());
        assertEquals(StandardCharsets.UTF_8.name(), response.getCharacterEncoding());
        assertTrue(response.getContentType().startsWith("application/json"));

        String body = new String(response.getContentAsByteArray(), StandardCharsets.UTF_8);
        JsonNode json = new ObjectMapper().readTree(body);

        assertEquals(ErrorCode.UNAUTHENTICATED.getCode(), json.get("code").asInt());
        assertEquals(ErrorCode.UNAUTHENTICATED.getMessage(), json.get("message").asText());
        assertTrue(body.contains(ErrorCode.UNAUTHENTICATED.getMessage()));
    }
}
