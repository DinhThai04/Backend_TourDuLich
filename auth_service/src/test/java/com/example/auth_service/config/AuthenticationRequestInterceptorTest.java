package com.example.auth_service.config;
import feign.RequestTemplate;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;
import static org.junit.jupiter.api.Assertions.*;
class AuthenticationRequestInterceptorTest {
    @AfterEach void cleanup(){RequestContextHolder.resetRequestAttributes();}
    @Test void safelyHandlesCallsOutsideHttpRequest() {
        var template=new RequestTemplate();assertDoesNotThrow(()->new AuthenticationRequestInterceptor().apply(template));assertTrue(template.headers().isEmpty());
    }
    @Test void forwardsAuthorizationWithoutPrintingIt() {
        var request=new MockHttpServletRequest();request.addHeader("Authorization","Bearer isolated-test-value");RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        var template=new RequestTemplate();var output=new java.io.ByteArrayOutputStream();var previous=System.out;
        try {System.setOut(new java.io.PrintStream(output));new AuthenticationRequestInterceptor().apply(template);}
        finally {System.setOut(previous);}
        assertEquals(java.util.List.of("Bearer isolated-test-value"),java.util.List.copyOf(template.headers().get("Authorization")));assertEquals("",output.toString());
    }
}
