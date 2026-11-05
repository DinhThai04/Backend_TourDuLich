package com.example.file_service;

import com.example.file_service.config.DownloadThrottle;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloadThrottleTest {

    private static MockHttpServletRequest request(String remoteAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/media/download/file.png");
        request.setRemoteAddr(remoteAddress);
        return request;
    }

    @Test
    void publicClientCannotPickItsBucketWithProxyHeaders() {
        DownloadThrottle throttle = new DownloadThrottle(1);
        var first = request("203.0.113.9");
        first.addHeader("X-Real-IP", "198.51.100.1");
        assertEquals(0, throttle.reserve(first));
        var second = request("203.0.113.9");
        second.addHeader("X-Real-IP", "198.51.100.2");
        assertTrue(throttle.reserve(second) > 0);
    }

    @Test
    void loopbackProxySeparatesRealClientsAndRepeatsAreRejected() {
        DownloadThrottle throttle = new DownloadThrottle(1);
        var first = request("127.0.0.1");
        first.addHeader("X-Real-IP", "198.51.100.1");
        assertEquals(0, throttle.reserve(first));
        var repeat = request("127.0.0.1");
        repeat.addHeader("X-Real-IP", "198.51.100.1");
        assertTrue(throttle.reserve(repeat) > 0);
        var other = request("127.0.0.1");
        other.addHeader("X-Real-IP", "198.51.100.2");
        assertEquals(0, throttle.reserve(other));
    }

    @Test
    void rightmostForwardedHopIsTheTrustedOne() {
        DownloadThrottle throttle = new DownloadThrottle(1);
        var first = request("10.0.0.5");
        first.addHeader("X-Forwarded-For", "6.6.6.6, 198.51.100.7");
        assertEquals(0, throttle.reserve(first));
        var spoofed = request("10.0.0.5");
        spoofed.addHeader("X-Forwarded-For", "203.0.113.99, 198.51.100.7");
        assertTrue(throttle.reserve(spoofed) > 0);
    }

    @Test
    void nginxRealIpWinsOverProxyAppendedForwardedChain() {
        DownloadThrottle throttle = new DownloadThrottle(1);
        // Chain seen by file-service: client supplied, nginx appended the client, gateway appended nginx.
        var first = request("10.0.0.5");
        first.addHeader("X-Forwarded-For", "6.6.6.6, 198.51.100.1, 10.0.0.4");
        first.addHeader("X-Real-IP", "198.51.100.1");
        assertEquals(0, throttle.reserve(first));
        var other = request("10.0.0.5");
        other.addHeader("X-Forwarded-For", "6.6.6.6, 198.51.100.2, 10.0.0.4");
        other.addHeader("X-Real-IP", "198.51.100.2");
        assertEquals(0, throttle.reserve(other));
    }

    @Test
    void zeroLimitDisablesTheThrottle() {
        DownloadThrottle throttle = new DownloadThrottle(0);
        for (int attempt = 0; attempt < 5; attempt++) {
            assertEquals(0, throttle.reserve(request("203.0.113.9")));
        }
    }
}
