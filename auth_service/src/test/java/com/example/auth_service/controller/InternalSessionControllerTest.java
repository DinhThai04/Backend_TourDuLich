package com.example.auth_service.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.auth_service.config.InternalAuthAuthenticationFilter;
import com.example.auth_service.config.AuthInternalSecurityProperties;
import com.example.auth_service.config.CustomJwtDecoder;
import com.example.auth_service.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(InternalSessionController.class)
@Import({SecurityConfig.class, InternalAuthAuthenticationFilter.class, AuthInternalSecurityProperties.class})
class InternalSessionControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CustomJwtDecoder jwtDecoder;

    @Test
    void acceptsOnlyAnInternallyAuthenticatedValidBearerSession() throws Exception {
        when(jwtDecoder.decode("active-token")).thenReturn(
                Jwt.withTokenValue("active-token").header("alg", "HS256").subject("17").build());

        mockMvc.perform(post("/internal/auth/sessions/validate")
                        .servletPath("/internal/auth/sessions/validate")
                        .header("X-Internal-Auth-Key", "test-internal-key")
                        .header("Authorization", "Bearer active-token"))
                .andExpect(status().isNoContent());
    }

    @Test
    void rejectsCallsWithoutInternalCredential() throws Exception {
        when(jwtDecoder.decode("active-token")).thenReturn(
                Jwt.withTokenValue("active-token").header("alg", "HS256").subject("17").build());
        mockMvc.perform(post("/internal/auth/sessions/validate")
                        .servletPath("/internal/auth/sessions/validate")
                        .header("Authorization", "Bearer active-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsInvalidBearerSessionEvenWithInternalCredential() throws Exception {
        when(jwtDecoder.decode("revoked-token")).thenThrow(new BadJwtException("revoked"));

        mockMvc.perform(post("/internal/auth/sessions/validate")
                        .servletPath("/internal/auth/sessions/validate")
                        .header("X-Internal-Auth-Key", "test-internal-key")
                        .header("Authorization", "Bearer revoked-token"))
                .andExpect(status().isUnauthorized());
    }
}
