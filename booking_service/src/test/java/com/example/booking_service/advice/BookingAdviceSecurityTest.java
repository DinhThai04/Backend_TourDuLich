package com.example.booking_service.advice;

import com.example.booking_service.config.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import java.time.OffsetDateTime;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers=BookingAdviceController.class)
@Import(SecurityConfig.class)
class BookingAdviceSecurityTest {
    @Autowired MockMvc mvc;
    @MockBean BookingAdviceService advice;
    @MockBean ReviewEligibilityService reviewEligibility;
    @MockBean CustomJwtDecoder decoder;
    @Test void directServiceDoesNotAcceptAnonymousOrInternalMailCredential() throws Exception {
        mvc.perform(get("/advice/mine")).andExpect(status().isUnauthorized());
        mvc.perform(get("/advice/mine").header("X-Internal-Mail-Key","not-a-jwt")).andExpect(status().isUnauthorized());
        verifyNoInteractions(advice);
    }
    @Test void authenticatedReadIsNoStoreAndDoesNotHaveWriteMethod() throws Exception {
        when(advice.mine(6)).thenReturn(new BookingAdviceService.Page(List.of(),false,OffsetDateTime.now()));
        mvc.perform(get("/advice/mine?bookingId=6").with(jwt().jwt(j->j.subject("7"))))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.code").value(1000));
        mvc.perform(post("/advice/mine").with(jwt().jwt(j->j.subject("7")))).andExpect(status().isMethodNotAllowed());
        verify(advice).mine(6);
    }
}
