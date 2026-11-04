package com.example.api_gateway.config;

import org.springframework.http.server.reactive.ServerHttpRequest;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/**
 * Resolves the caller address behind the bundled nginx. Proxy headers are trusted only when the
 * socket itself is a loopback/private address; a public caller therefore cannot choose its own
 * rate-limit or AI-owner key.
 */
final class ClientAddress {
    private static final Pattern IPV4 = Pattern.compile(
            "(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}");
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:.]{2,45}");

    private ClientAddress() {}

    static String of(ServerHttpRequest request) {
        InetSocketAddress remote = request.getRemoteAddress();
        if (remote == null || remote.getAddress() == null) return "unknown";
        String remoteIp = remote.getAddress().getHostAddress();
        if (!isTrustedProxy(remote.getAddress())) return remoteIp;

        // The proxy appends the socket peer to X-Forwarded-For, so the rightmost hop is the one
        // part of the header our deployment controls. X-Real-IP is only a fallback because a
        // proxy that does not overwrite it would let the caller choose its own rate-limit key.
        String forwarded = request.getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null) {
            String[] hops = forwarded.split(",");
            for (int index = hops.length - 1; index >= 0; index--) {
                String hop = normalizeIp(hops[index]);
                if (hop != null) return hop;
            }
        }

        String realIp = normalizeIp(request.getHeaders().getFirst("X-Real-IP"));
        if (realIp != null) return realIp;

        return remoteIp;
    }

    private static boolean isTrustedProxy(InetAddress address) {
        return address.isLoopbackAddress() || address.isSiteLocalAddress();
    }

    /** Accepts literal IPv4/IPv6 only, so header contents can never trigger a DNS lookup. */
    private static String normalizeIp(String candidate) {
        if (candidate == null) return null;
        String value = candidate.trim();
        if (value.isEmpty() || value.length() > 64) return null;
        if (!IPV4.matcher(value).matches() && !(value.indexOf(':') >= 0 && IPV6.matcher(value).matches()))
            return null;
        try {
            return InetAddress.getByName(value).getHostAddress();
        } catch (UnknownHostException invalid) {
            return null;
        }
    }
}
