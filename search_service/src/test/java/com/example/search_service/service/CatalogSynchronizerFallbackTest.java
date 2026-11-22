package com.example.search_service.service;

import com.example.search_service.document.TourDocument;
import com.example.search_service.repository.TourSearchRepository;
import com.example.search_service.service.CatalogSynchronizer;
import com.example.search_service.service.CatalogSnapshotReader;
import com.example.search_service.service.TourSearchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CatalogSynchronizerFallbackTest {

    TourSearchRepository repo = mock(TourSearchRepository.class);
    ElasticsearchOperations es = mock(ElasticsearchOperations.class);
    @SuppressWarnings("unchecked") RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
    @SuppressWarnings("unchecked") ValueOperations<String, Object> values = mock(ValueOperations.class);
    TourSearchService service = new TourSearchService(repo, es, redis);
    ObjectMapper mapper = new ObjectMapper();
    HttpServer server;

    @BeforeEach void setup() {
        when(redis.opsForValue()).thenReturn(values);
        when(es.indexOps(TourDocument.class)).thenReturn(mock(org.springframework.data.elasticsearch.core.IndexOperations.class));
    }

    @AfterEach void stop() {
        if (server != null) server.stop(0);
    }

    @Test void syncDeferredWhenTourServiceUnavailableDoesNotMutateIndex() throws Exception {
        // Tour service returns 503
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/tour/tours/search-snapshot", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();

        var reader = new CatalogSnapshotReader(mapper, "http://127.0.0.1:" + server.getAddress().getPort() + "/tour/tours/search-snapshot", "");
        var sync = new CatalogSynchronizer(reader, service, true);

        assertThrows(ResponseStatusException.class, sync::sync);
        verifyNoInteractions(repo, es, redis);
    }

    @Test void syncDeferredWhenTourServiceTimeoutDoesNotMutateIndex() throws Exception {
        // Tour service is slow (timeout)
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
        var sync = new CatalogSynchronizer(reader, service, true);

        assertThrows(ResponseStatusException.class, sync::sync);
        verifyNoInteractions(repo, es, redis);
    }

    @Test void pollMethodCatchesExceptionAndLogsWarning() throws Exception {
        // When poll() encounters an error, it should catch and log, not crash the scheduler
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/tour/tours/search-snapshot", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();

        var reader = new CatalogSnapshotReader(mapper, "http://127.0.0.1:" + server.getAddress().getPort() + "/tour/tours/search-snapshot", "");
        var sync = new CatalogSynchronizer(reader, service, true);

        // poll() should not throw, it catches RuntimeException internally
        assertDoesNotThrow(sync::poll);
        verifyNoInteractions(repo, es, redis);
    }

    @Test void forcedSyncViaAdminEndpointStillAttemptsSync() throws Exception {
        // Even if previous polls failed, admin sync should still attempt
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/tour/tours/search-snapshot", exchange -> {
            String body = "{\"source\":\"SGU_TOUR_CATALOG_V1\",\"complete\":true,\"total\":1,\"tours\":[" +
                    "{\"id\":1,\"tourProgram\":\"P1\",\"tourTitle\":\"Tour\",\"basePrice\":3000000,\"departureDates\":[\"2026-11-20\"]}]}";
            byte[] b = body.getBytes();
            exchange.sendResponseHeaders(200, b.length);
            exchange.getResponseBody().write(b);
            exchange.close();
        });
        server.start();

        var reader = new CatalogSnapshotReader(mapper, "http://127.0.0.1:" + server.getAddress().getPort() + "/tour/tours/search-snapshot", "");
        var sync = new CatalogSynchronizer(reader, service, true);

        when(repo.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        var result = sync.sync();
        assertEquals(1, result.updated());
        verify(repo).saveAll(any());
    }
}