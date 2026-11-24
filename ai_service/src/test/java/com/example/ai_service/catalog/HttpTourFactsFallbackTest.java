package com.example.ai_service.catalog;

import com.example.ai_service.catalog.CatalogConfiguration;
import com.example.ai_service.catalog.TourFactsReader.Facts;
import com.example.ai_service.conversation.ChatFailure;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class HttpTourFactsFallbackTest {

    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final Clock clock = Clock.fixed(Instant.parse("2026-10-23T01:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));
    HttpServer server;

    @AfterEach void stop() {
        if (server != null) server.stop(0);
    }

    @Test void factsReaderReturnsCatalogUnavailableWhenTourServiceDown() throws Exception {
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

    @Test void upcomingDeparturesReturnsCatalogUnavailableWhenTourServiceDown() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();

        var reader = new CatalogConfiguration().tourFactsReader("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/tour", json, clock);
        var ex = assertThatThrownBy(() -> reader.upcomingDepartures(901));
        ex.hasMessage("CATALOG_UNAVAILABLE");
    }

    @Test void meetingPointReturnsCatalogUnavailableWhenTourServiceDown() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();

        var reader = new CatalogConfiguration().tourFactsReader("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/tour", json, clock);
        var ex = assertThatThrownBy(() -> reader.meetingPoint(901, 1));
        ex.hasMessage("CATALOG_UNAVAILABLE");
    }

    @Test void detailReturnsCatalogUnavailableWhenTourServiceDown() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();

        var reader = new CatalogConfiguration().tourFactsReader("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/tour", json, clock);
        var ex = assertThatThrownBy(() -> reader.detail(new Facts(901, "Tour", 3, "City", 2600000L, OffsetDateTime.now(clock), List.of())));
        ex.hasMessage("CATALOG_UNAVAILABLE");
    }

    @Test void partialFallbackForPageFactsWhenPriceMissing() throws Exception {
        // pageFacts allows partial data (allowPartial=true)
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body;
            if (path.contains("/tours/")) {
                body = """
                    {"code":1000,"result":{"id":901,"tourTitle":"Tour","duration":3,
                      "basePrice":999,"destinationCity":{"city":"City"},"tourPrice":{},
                      "departures":[{"id":8,"tourId":901,"departureDate":"2026-11-01T07:00:00","returnDate":"2026-11-03T17:00:00","availableSeats":10}]}}
                    """;
            } else {
                body = "{\"code\":1000,\"result\":[]}";
            }
            byte[] bytes = body.getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        var reader = new CatalogConfiguration().tourFactsReader("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/tour", json, clock);
        // pageFacts should succeed even without price
        var facts = reader.pageFacts(901);
        assertThat(facts).isNotNull();
        assertThat(facts.adultPriceVnd()).isNull();
        assertThat(facts.title()).isEqualTo("Tour");
    }

    @Test void factsReaderFailsFastForInvalidData() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            // Missing required field: duration
            String body = "{\"code\":1000,\"result\":{\"id\":901,\"tourTitle\":\"Tour\",\"basePrice\":999}}";
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
}