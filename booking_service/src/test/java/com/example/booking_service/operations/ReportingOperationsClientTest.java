package com.example.booking_service.operations;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportingOperationsClientTest {

    private static final String KEY = "0123456789abcdef0123456789abcdef";

    @Test
    void callsReportingWithInternalKeyAndRange() throws Exception {
        AtomicReference<String> key = new AtomicReference<>();
        AtomicReference<String> query = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/reports/operations", exchange -> {
            key.set(exchange.getRequestHeaders().getFirst("X-Internal-Reporting-Key"));
            query.set(exchange.getRequestURI().getQuery());
            byte[] body = "{\"bookings\":2,\"collected\":3000000}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ReportingOperationsClient client = new ReportingOperationsClient(RestClient.builder(),
                    "http://127.0.0.1:" + server.getAddress().getPort(), KEY, true);

            Map<String, Object> result = client.report(LocalDate.of(2026, 11, 1),
                    LocalDate.of(2026, 11, 30)).orElseThrow();

            assertEquals(KEY, key.get());
            assertTrue(query.get().contains("from=2026-11-01"));
            assertTrue(query.get().contains("through=2026-11-30"));
            assertEquals(2, result.get("bookings"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void staysDisabledWithoutConfiguredKey() {
        ReportingOperationsClient client = new ReportingOperationsClient(RestClient.builder(),
                "http://127.0.0.1:1", "", true);

        assertTrue(client.report(LocalDate.now(), LocalDate.now()).isEmpty());
    }
}
