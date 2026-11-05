package com.example.invoice_service.config;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;
import feign.RequestTemplate;
import static org.junit.jupiter.api.Assertions.*;
class HeaderForwardingTest {
    @AfterEach void clear(){RequestContextHolder.resetRequestAttributes();}
    @Test void missingRequestIsSafeAndHeadersAreNeverPrinted(){
        assertDoesNotThrow(()->new AuthenticationRequestInterceptor().apply(new RequestTemplate()));
        var request=new MockHttpServletRequest();request.addHeader("Authorization","Bearer isolated-header-test");RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        var buffer=new java.io.ByteArrayOutputStream();var previous=System.out;var template=new RequestTemplate();
        try {System.setOut(new java.io.PrintStream(buffer));new AuthenticationRequestInterceptor().apply(template);}
        finally {System.setOut(previous);}
        assertEquals("",buffer.toString());assertEquals(java.util.List.of("Bearer isolated-header-test"),java.util.List.copyOf(template.headers().get("Authorization")));
    }
}
