package com.example.tour_service;

import com.example.tour_service.config.FileInternalFeignConfig;
import com.example.tour_service.repository.httpclient.FileInternalClient;
import com.sun.net.httpserver.HttpServer;
import feign.Feign;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.support.SpringMvcContract;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FileInternalFeignTest {
    @Test
    void galleryDetachRequestUsesInternalKeyAndEventIdentity() throws Exception {
        var path = new AtomicReference<String>();
        var internalKey = new AtomicReference<String>();
        var eventId = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/file/internal/file/files/detach-tour-gallery/123", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            internalKey.set(exchange.getRequestHeaders().getFirst("X-Internal-File-Key"));
            eventId.set(exchange.getRequestHeaders().getFirst("X-Event-Id"));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();

        try {
            var configuration = new FileInternalFeignConfig();
            ReflectionTestUtils.setField(configuration, "fileInternalKey", "test-file-key");
            var client = Feign.builder()
                    .contract(new SpringMvcContract())
                    .requestInterceptor(configuration.fileInternalRequestInterceptor())
                    .target(FileInternalClient.class, "http://127.0.0.1:" + server.getAddress().getPort());

            client.detachTourGallery("123", "event-123");

            assertEquals("/file/internal/file/files/detach-tour-gallery/123", path.get());
            assertEquals("test-file-key", internalKey.get());
            assertEquals("event-123", eventId.get());
        } finally {
            server.stop(0);
        }
    }
}
