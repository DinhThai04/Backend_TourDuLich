package com.example.tour_service.advice;

import com.example.tour_service.config.AuthenticationRequestInterceptor;
import feign.RequestTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import static org.junit.jupiter.api.Assertions.*;

class AdviceInterceptorTest {
    @AfterEach void cleanup(){RequestContextHolder.resetRequestAttributes();}
    @Test void toolCallWithoutServletContextDoesNotCrash() {
        RequestContextHolder.resetRequestAttributes();var request=new RequestTemplate();
        assertDoesNotThrow(()->new AuthenticationRequestInterceptor().apply(request));assertTrue(request.headers().isEmpty());
    }
    @Test void existingAuthenticatedCallsStillForwardHeader() {
        var http=new MockHttpServletRequest();http.addHeader("Authorization","Bearer test-only");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(http));var request=new RequestTemplate();
        new AuthenticationRequestInterceptor().apply(request);assertEquals("Bearer test-only",request.headers().get("Authorization").iterator().next());
    }
}
