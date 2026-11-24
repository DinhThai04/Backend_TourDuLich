package com.example.tour_service;

import com.example.tour_service.config.PricingFeignConfiguration;
import com.example.tour_service.dto.internal.InternalPriceResponse;
import com.example.tour_service.repository.httpclient.PricingInternalClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import feign.Feign;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.support.SpringMvcContract;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PricingInternalFeignTest {
    @Test
    void batchRequestUsesAuthenticatedInternalContract() throws Exception {
        var path = new AtomicReference<String>();
        var key = new AtomicReference<String>();
        var body = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/pricing/prices/batch", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            key.set(exchange.getRequestHeaders().getFirst("X-Internal-Pricing-Key"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "[{\"id\":11,\"adultPrice\":3550000.00}]".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();

        try {
            var mapper = new ObjectMapper();
            var configuration = new PricingFeignConfiguration();
            ReflectionTestUtils.setField(configuration, "pricingInternalKey", "test-pricing-key");
            var client = Feign.builder()
                    .contract(new SpringMvcContract())
                    .requestInterceptor(configuration.pricingInternalRequestInterceptor())
                    .encoder((request, bodyType, template) -> {
                        try {
                            template.body(mapper.writeValueAsString(request));
                        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                            throw new IllegalArgumentException("Could not encode pricing request", exception);
                        }
                    })
                    .decoder((response, type) -> mapper.readValue(
                            response.body().asInputStream(), mapper.constructType(type)))
                    .target(PricingInternalClient.class,
                            "http://127.0.0.1:" + server.getAddress().getPort());

            List<InternalPriceResponse> prices = client.getPricesBatch(List.of(11L));

            assertEquals("/internal/pricing/prices/batch", path.get());
            assertEquals("test-pricing-key", key.get());
            assertEquals("[11]", body.get());
            assertEquals(11L, prices.getFirst().id());
        } finally {
            server.stop(0);
        }
    }
}
