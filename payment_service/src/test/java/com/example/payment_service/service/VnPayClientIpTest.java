package com.example.payment_service.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** vnp_IpAddr must carry the customer address, not the proxy hop our own chain appends. */
class VnPayClientIpTest {
    private static MockHttpServletRequest throughProxyChain(String forwardedFor, String realIp) {
        var request = new MockHttpServletRequest();
        // payment_service is reached from the gateway, so the socket peer is always a local address.
        request.setRemoteAddr("172.18.0.5");
        if (forwardedFor != null) request.addHeader("X-Forwarded-For", forwardedFor);
        if (realIp != null) request.addHeader("X-Real-IP", realIp);
        return request;
    }

    @Test
    void nginxRealIpWinsOverTheGatewayHopAppendedToForwardedFor() {
        var request = throughProxyChain("203.0.113.9, 172.18.0.2", "203.0.113.9");
        assertEquals("203.0.113.9", VnPayService.clientIp(request));
    }

    @Test
    void rightmostCustomerHopIsUsedWhenXRealIpIsMissingAndSpoofedLeftHopsAreIgnored() {
        var request = throughProxyChain("8.8.8.8, 203.0.113.9, 172.18.0.2", null);
        assertEquals("203.0.113.9", VnPayService.clientIp(request));
    }

    @Test
    void lanClientAddressIsKeptWhenNoPublicHopExists() {
        var request = throughProxyChain("192.168.1.20, 127.0.0.1", null);
        assertEquals("192.168.1.20", VnPayService.clientIp(request));
    }

    @Test
    void headersAreIgnoredWhenThePeerIsAlreadyTheCustomer() {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.44");
        request.addHeader("X-Forwarded-For", "8.8.8.8");
        request.addHeader("X-Real-IP", "8.8.4.4");
        assertEquals("203.0.113.44", VnPayService.clientIp(request));
    }

    @Test
    void ipv6CustomerFallsBackToLocalhostBecauseVnPayNeedsIpv4() {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("2600::1");
        assertEquals("127.0.0.1", VnPayService.clientIp(request));
    }
}
