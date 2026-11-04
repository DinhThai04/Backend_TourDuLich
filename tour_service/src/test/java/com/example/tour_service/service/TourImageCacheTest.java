package com.example.tour_service.service;

import com.example.tour_service.dto.response.TourFileResponse;
import com.example.tour_service.repository.TourRepository;
import com.example.tour_service.repository.httpClient.FileClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TourImageCacheTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private FileClient fileClient;
    private TourRepository tourRepository;
    private TourImageCache cache;

    @BeforeEach
    void setUp() {
        fileClient = mock(FileClient.class);
        tourRepository = mock(TourRepository.class);
        when(tourRepository.findAllIds()).thenReturn(List.of(1, 2, 7));
        cache = newCache(fileClient);
    }

    @Test
    void groupsUsableUrlsByTourId() {
        when(fileClient.getTourMedia(anyList())).thenReturn(List.of(
                new TourFileResponse("1", "https://cdn/a.jpg"),
                new TourFileResponse("1", "https://cdn/b.jpg"),
                new TourFileResponse("2", "https://cdn/c.jpg"),
                new TourFileResponse("2", ""),
                new TourFileResponse(null, "https://cdn/orphan.jpg")));

        cache.refresh();
        Map<String, List<String>> images = cache.byTourId();

        assertEquals(List.of("https://cdn/a.jpg", "https://cdn/b.jpg"), images.get("1"));
        assertEquals(List.of("https://cdn/c.jpg"), images.get("2"));
        assertEquals(2, images.size());
    }

    @Test
    void tourReadsNeverCallFileService() {
        when(fileClient.getTourMedia(anyList())).thenReturn(List.of(new TourFileResponse("1", "https://cdn/a.jpg")));
        cache.refresh();

        cache.byTourId();
        cache.byTourId();

        verify(fileClient, times(1)).getTourMedia(anyList());
    }

    @Test
    void keepsPreviousSnapshotWhenFileServiceFails() {
        when(fileClient.getTourMedia(anyList())).thenReturn(List.of(new TourFileResponse("1", "https://cdn/a.jpg")));
        cache.refresh();

        when(fileClient.getTourMedia(anyList())).thenThrow(new IllegalStateException("file-service down"));
        cache.refresh();

        assertEquals(List.of("https://cdn/a.jpg"), cache.byTourId().get("1"));
    }

    @Test
    void coldStartFailureReturnsEmptySnapshotInsteadOfFailingTourReads() {
        when(fileClient.getTourMedia(anyList())).thenThrow(new IllegalStateException("file-service down"));

        cache.refresh();

        assertTrue(cache.byTourId().isEmpty());
    }

    @Test
    void servesThePersistedSnapshotAfterRestartWithoutFileService() {
        when(fileClient.getTourMedia(anyList())).thenReturn(List.of(new TourFileResponse("1", "https://cdn/a.jpg")));
        cache.refresh();

        FileClient offline = mock(FileClient.class);
        TourImageCache restarted = newCache(offline);

        assertEquals(List.of("https://cdn/a.jpg"), restarted.byTourId().get("1"));
    }

    @Test
    void uploadResultIsVisibleImmediatelyAndPersisted() {
        cache.update("7", List.of("https://cdn/new.jpg"));

        assertEquals(List.of("https://cdn/new.jpg"), cache.byTourId().get("7"));
        assertEquals(List.of("https://cdn/new.jpg"), newCache(fileClient).byTourId().get("7"));

        cache.update("7", List.of());

        assertTrue(cache.byTourId().isEmpty());
    }

    private TourImageCache newCache(FileClient client) {
        return new TourImageCache(client, tourRepository, objectMapper,
                tempDir.resolve("tour-images.json").toString());
    }
}
