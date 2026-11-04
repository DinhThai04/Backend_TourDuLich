package com.example.file_service.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * In-memory throttle for the public download endpoint. file-service serves media directly (there is
 * no CDN in front of it), so one client looping large files must be bounded per instance.
 *
 * <p>Proxy headers are trusted only when the socket itself is loopback/private, exactly like the
 * gateway's {@code ClientAddress}: a public caller cannot pick its own bucket.
 */
@Component
public class DownloadThrottle {
    private static final Pattern IPV4 = Pattern.compile(
            "(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}");
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:.]{2,45}");
    private static final int MAX_CLIENTS = 10_000;
    private static final long WINDOW_MILLIS = 60_000L;

    private record Window(long startedAt, int count) {
    }

    private final int limit;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public DownloadThrottle(@Value("${app.file.downloads-per-minute:60}") int downloadsPerMinute) {
        // 0 disables the throttle for deployments where a CDN fronts this endpoint.
        this.limit = Math.max(0, downloadsPerMinute);
    }

    /** @return 0 when the download may proceed, otherwise the Retry-After value in seconds */
    public long reserve(HttpServletRequest request) {
        if (limit == 0) {
            return 0;
        }
        long now = System.currentTimeMillis();
        if (windows.size() > MAX_CLIENTS) {
            windows.entrySet().removeIf(entry -> now - entry.getValue().startedAt() >= WINDOW_MILLIS);
        }
        Window window = windows.compute(clientOf(request), (key, current) ->
                current == null || now - current.startedAt() >= WINDOW_MILLIS
                        ? new Window(now, 1)
                        : new Window(current.startedAt(), current.count() + 1));
        if (window.count() <= limit) {
            return 0;
        }
        return Math.max(1L, (window.startedAt() + WINDOW_MILLIS - now + 999L) / 1000L);
    }

    static String clientOf(HttpServletRequest request) {
        String remoteIp = normalize(request.getRemoteAddr());
        if (remoteIp == null) {
            return "unknown";
        }
        if (!isTrustedProxy(remoteIp)) {
            return remoteIp;
        }
        // nginx overwrites X-Real-IP with the browser address and the gateway forwards it untouched.
        // X-Forwarded-For is only the fallback: with nginx -> gateway -> file two hops are appended,
        // so its rightmost entry is the proxy itself and every client would share one bucket.
        String realIp = normalize(request.getHeader("X-Real-IP"));
        if (realIp != null) {
            return realIp;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null) {
            String[] hops = forwarded.split(",");
            for (int index = hops.length - 1; index >= 0; index--) {
                String hop = normalize(hops[index]);
                if (hop != null) {
                    return hop;
                }
            }
        }
        return remoteIp;
    }

    private static boolean isTrustedProxy(String address) {
        try {
            InetAddress parsed = InetAddress.getByName(address);
            return parsed.isLoopbackAddress() || parsed.isSiteLocalAddress();
        } catch (UnknownHostException invalid) {
            return false;
        }
    }

    /** Accepts literal IPv4/IPv6 only, so header contents can never trigger a DNS lookup. */
    private static String normalize(String candidate) {
        if (candidate == null) {
            return null;
        }
        String value = candidate.trim();
        if (value.isEmpty() || value.length() > 64) {
            return null;
        }
        if (!IPV4.matcher(value).matches() && !(value.indexOf(':') >= 0 && IPV6.matcher(value).matches())) {
            return null;
        }
        try {
            return InetAddress.getByName(value).getHostAddress();
        } catch (UnknownHostException invalid) {
            return null;
        }
    }
}
