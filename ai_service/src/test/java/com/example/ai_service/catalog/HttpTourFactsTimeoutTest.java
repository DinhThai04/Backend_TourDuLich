package com.example.ai_service.catalog;

import com.example.ai_service.catalog.CatalogConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.*;
import java.time.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

class HttpTourFactsTimeoutTest {

    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final Clock clock = Clock.fixed(Instant.parse("2026-10-23T01:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));
    HttpServer server;

    @AfterEach void stop() {
        if (server != null) server.stop(0);
    }

    @Test void connectTimeoutIsEnforcedWhenTourServiceIsUnreachable() {
        // Use 127.0.0.1 with a port nothing is listening on - connection will be refused quickly
        // but LocalServiceUrl validation will pass
        var reader = new CatalogConfiguration().tourFactsReader("http://127.0.0.1:9999/api/v1/tour", json, clock);
        var ex = assertThatThrownBy(() -> reader.facts(901));
        ex.hasMessage("CATALOG_UNAVAILABLE");
    }

    @Test void readTimeoutIsEnforcedWhenTourServiceIsSlow() throws Exception {
        var status = new AtomicInteger(200);
        var bodyRef = new AtomicReference<String>("""
            {"code":1000,"result":{"id":901,"tourTitle":"Tour","duration":3,
              "basePrice":999,"destinationCity":{"city":"City"},"tourPrice":{"adultPrice":2600000},
              "departures":[{"id":8,"tourId":901,"departureDate":"2026-11-01T07:00:00","returnDate":"2026-11-03T17:00:00","availableSeats":10}]}}
            """);
        
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            try { Thread.sleep(10000); } catch (InterruptedException ignored) {}
            byte[] bytes = bodyRef.get().getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        var reader = new CatalogConfiguration().tourFactsReader("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/tour", json, clock);
        var ex = assertThatThrownBy(() -> reader.facts(901));
        ex.hasMessage("CATALOG_UNAVAILABLE");
    }

    @Test void tourService503ReturnsCatalogUnavailable() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();

        var reader = new CatalogConfiguration().tourFactsReader("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/tour", json, clock);
        var ex = assertThatThrownBy(() -> reader.facts(901));
        ex.hasMessage("CATALOG_UNAVAILABLE");
    }

    @Test void tourService404ForMissingTourReturnsNull() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = "{\"code\":1001}";
            byte[] bytes = body.getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(404, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        var reader = new CatalogConfiguration().tourFactsReader("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/tour", json, clock);
        assertThat(reader.facts(901)).isNull();
    }

    @Test void tourService400WithCode1001ReturnsNull() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = "{\"code\":1001}";
            byte[] bytes = body.getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(400, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        var reader = new CatalogConfiguration().tourFactsReader("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/tour", json, clock);
        assertThat(reader.facts(901)).isNull();
    }

    @Test void malformedResponseIsRejected() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = "not-json";
            byte[] bytes = body.getBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        var reader = new CatalogConfiguration().tourFactsReader("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/tour", json, clock);
        var ex = assertThatThrownBy(() -> reader.facts(901));
        ex.hasMessage("CATALOG_UNAVAILABLE");
    }

    @Test void oversizedResponseBodyIsRejected() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = "x".repeat(1_048_577);
            byte[] bytes = body.getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        var reader = new CatalogConfiguration().tourFactsReader("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/tour", json, clock);
        var ex = assertThatThrownBy(() -> reader.facts(901));
        ex.hasMessage("CATALOG_UNAVAILABLE");
    }

    @Test void missingRequiredFieldsInTourDataIsRejected() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = "{\"code\":1000,\"result\":{\"id\":901,\"tourTitle\":\"Tour\"}}";
            byte[] bytes = body.getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        var reader = new CatalogConfiguration().tourFactsReader("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/tour", json, clock);
        var ex = assertThatThrownBy(() -> reader.facts(901));
        ex.hasMessage("CATALOG_UNAVAILABLE");
    }

    private final AtomicInteger calls = new AtomicInteger();
}