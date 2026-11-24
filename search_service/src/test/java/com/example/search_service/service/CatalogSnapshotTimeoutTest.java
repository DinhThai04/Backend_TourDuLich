package com.example.search_service.service;

import com.example.search_service.document.TourDocument;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.*;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CatalogSnapshotTimeoutTest {

    final ObjectMapper mapper = new ObjectMapper();
    HttpServer server;

    @AfterEach void stop() {
        if (server != null) server.stop(0);
    }

    @Test void connectTimeoutIsEnforcedWhenTourServiceIsUnreachable() throws Exception {
        // Use an unreachable IP that will cause connect timeout
        var reader = new CatalogSnapshotReader(mapper, "http://10.255.255.1:8086/tour/tours/search-snapshot", "");
        assertThrows(IllegalStateException.class, reader::read);
    }

    @Test void readTimeoutIsEnforcedWhenTourServiceIsSlow() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/tour/tours/search-snapshot", exchange -> {
            try { Thread.sleep(10000); } catch (InterruptedException ignored) {}
            byte[] b = "{}".getBytes();
            exchange.sendResponseHeaders(200, b.length);
            exchange.getResponseBody().write(b);
            exchange.close();
        });
        server.start();

        var reader = new CatalogSnapshotReader(mapper, "http://127.0.0.1:" + server.getAddress().getPort() + "/tour/tours/search-snapshot", "");
        var ex = assertThrows(IllegalStateException.class, reader::read);
        assertTrue(ex.getMessage().contains("Could not read complete catalog snapshot") || ex.getCause() instanceof java.net.SocketTimeoutException);
    }

    @Test void incompleteSnapshotIsRejectedGracefully() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/tour/tours/search-snapshot", exchange -> {
            String body = "{\"source\":\"SGU_TOUR_CATALOG_V1\",\"complete\":false,\"total\":0,\"tours\":[]}";
            byte[] b = body.getBytes();
            exchange.sendResponseHeaders(200, b.length);
            exchange.getResponseBody().write(b);
            exchange.close();
        });
        server.start();

        var reader = new CatalogSnapshotReader(mapper, "http://127.0.0.1:" + server.getAddress().getPort() + "/tour/tours/search-snapshot", "");
        var ex = assertThrows(IllegalStateException.class, reader::read);
        // The reader wraps all exceptions, check cause message
        assertTrue(ex.getMessage().contains("Could not read complete catalog snapshot"));
        assertTrue(ex.getCause().getMessage().contains("Incomplete catalog snapshot"));
    }

    @Test void oversizedSnapshotIsRejectedBeforeParsing() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/tour/tours/search-snapshot", exchange -> {
            // Create a response > 8MB
            String tours = "{\"id\":1,\"tourProgram\":\"P1\",\"tourTitle\":\"Tour\",\"basePrice\":3000000,\"departureDates\":[\"2026-11-20\"]}".repeat(200000);
            String body = "{\"source\":\"SGU_TOUR_CATALOG_V1\",\"complete\":true,\"total\":10000,\"tours\":[" + tours + "]}";
            byte[] b = body.getBytes();
            exchange.sendResponseHeaders(200, b.length);
            exchange.getResponseBody().write(b);
            exchange.close();
        });
        server.start();

        var reader = new CatalogSnapshotReader(mapper, "http://127.0.0.1:" + server.getAddress().getPort() + "/tour/tours/search-snapshot", "");
        var ex = assertThrows(IllegalStateException.class, reader::read);
        assertTrue(ex.getMessage().contains("Could not read complete catalog snapshot"));
        assertTrue(ex.getCause().getMessage().contains("exceeds snapshot bound"));
    }

    @Test void non200ResponseIsTreatedAsUnavailable() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/tour/tours/search-snapshot", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();

        var reader = new CatalogSnapshotReader(mapper, "http://127.0.0.1:" + server.getAddress().getPort() + "/tour/tours/search-snapshot", "");
        var ex = assertThrows(IllegalStateException.class, reader::read);
        assertTrue(ex.getMessage().contains("Could not read complete catalog snapshot"));
        assertTrue(ex.getCause().getMessage().contains("Catalog unavailable"));
    }

    @Test void catalogKeyHeaderIsSentWhenConfigured() throws Exception {
        var seen = new java.util.concurrent.atomic.AtomicReference<String>();
        String body = "{\"source\":\"SGU_TOUR_CATALOG_V1\",\"complete\":true,\"total\":1,\"tours\":[" +
                "{\"id\":1,\"tourProgram\":\"P1\",\"tourTitle\":\"Tour\",\"basePrice\":3000000,\"departureDates\":[\"2026-11-20\"]}]}";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/tour/tours/search-snapshot", exchange -> {
            seen.set(exchange.getRequestHeaders().getFirst("X-Catalog-Key"));
            byte[] b = body.getBytes();
            exchange.sendResponseHeaders(200, b.length);
            exchange.getResponseBody().write(b);
            exchange.close();
        });
        server.start();

        var reader = new CatalogSnapshotReader(mapper, "http://127.0.0.1:" + server.getAddress().getPort() + "/tour/tours/search-snapshot", "test-catalog-key");
        assertEquals(1, reader.read().size());
        assertEquals("test-catalog-key", seen.get());
    }
}