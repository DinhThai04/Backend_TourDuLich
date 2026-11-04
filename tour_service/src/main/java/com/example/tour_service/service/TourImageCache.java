package com.example.tour_service.service;

import com.example.tour_service.dto.response.TourFileResponse;
import com.example.tour_service.repository.TourRepository;
import com.example.tour_service.repository.httpClient.FileClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Keeps tour images available even when file-service is slow or offline.
 *
 * <p>The mapping is refreshed in the background, so tour detail, list and search requests
 * never wait for file-service. Every successful refresh is also written to disk and reloaded
 * on startup; a restart while file-service is down still serves the last known images.
 */
@Component
@Slf4j
public class TourImageCache {

    private static final TypeReference<Map<String, List<String>>> SNAPSHOT_TYPE = new TypeReference<>() {
    };
    /** file-service accepts at most 50 tour IDs per read. */
    private static final int MEDIA_BATCH_SIZE = 50;

    private final FileClient fileClient;
    private final TourRepository tourRepository;
    private final ObjectMapper objectMapper;
    private final Path snapshotFile;

    private volatile Map<String, List<String>> imagesByTourId;
    /** Bumped by every local edit so a refresh that started earlier cannot overwrite it. */
    private long generation;

    public TourImageCache(FileClient fileClient,
            TourRepository tourRepository,
            ObjectMapper objectMapper,
            @Value("${app.tour-images.snapshot-file:data/tour-images.json}") String snapshotFile) {
        this.fileClient = fileClient;
        this.tourRepository = tourRepository;
        this.objectMapper = objectMapper;
        this.snapshotFile = Path.of(snapshotFile);
        this.imagesByTourId = readSnapshot();
    }

    /** A read-only snapshot; tour pages never call file-service on the request path. */
    public Map<String, List<String>> byTourId() {
        return imagesByTourId;
    }

    /** Refreshes in the background on a fixed delay; a failure keeps serving the previous snapshot. */
    @Scheduled(initialDelayString = "${app.tour-images.refresh-delay-ms:5000}",
            fixedDelayString = "${app.tour-images.refresh-interval-ms:300000}")
    public void refresh() {
        long generationBefore;
        synchronized (this) {
            generationBefore = generation;
        }
        Map<String, List<String>> loaded;
        try {
            // Remote calls stay outside the monitor so an upload/edit is never blocked by a slow
            // file-service; only the snapshot swap is synchronized.
            loaded = load();
        } catch (RuntimeException failure) {
            log.warn("Cannot refresh tour images from file-service; keeping {} cached tour(s): {}",
                    imagesByTourId.size(), failure.toString());
            return;
        }
        if (loaded == null) {
            // A batch came back empty or missing while we already had images for those tours;
            // that is a file-service hiccup, not every tour losing its media.
            log.warn("File-service returned an incomplete tour image payload; keeping {} cached tour(s)",
                    imagesByTourId.size());
            return;
        }
        synchronized (this) {
            if (generation != generationBefore) {
                // An upload/edit landed after this refresh read file-service; its newer state
                // must win, and the next scheduled refresh will pick up everything else.
                log.info("Tour images changed during refresh; discarding the older batch");
                return;
            }
            if (loaded.isEmpty() && !imagesByTourId.isEmpty()) {
                log.warn("File-service returned no tour images; keeping {} cached tour(s)",
                        imagesByTourId.size());
                return;
            }
            imagesByTourId = loaded;
            writeSnapshot(loaded);
        }
    }

    /**
     * Applies the image list returned by an upload/edit, so new images are visible immediately
     * without another file-service call.
     */
    public synchronized void update(String tourId, List<String> urls) {
        if (tourId == null || tourId.isBlank()) {
            return;
        }
        Map<String, List<String>> updated = new LinkedHashMap<>(imagesByTourId);
        if (urls == null || urls.isEmpty()) {
            updated.remove(tourId);
        } else {
            updated.put(tourId, List.copyOf(urls));
        }
        generation++;
        imagesByTourId = updated;
        writeSnapshot(updated);
    }

    /** Returns null when a batch is missing media that the current snapshot still has. */
    private Map<String, List<String>> load() {
        List<String> tourIds = tourRepository.findAllIds().stream().map(String::valueOf).toList();
        List<TourFileResponse> files = new ArrayList<>();
        for (int start = 0; start < tourIds.size(); start += MEDIA_BATCH_SIZE) {
            List<String> batchIds = tourIds.subList(start, Math.min(start + MEDIA_BATCH_SIZE, tourIds.size()));
            List<TourFileResponse> batch = fileClient.getTourMedia(batchIds);
            if (batch == null || (batch.isEmpty() && batchIds.stream().anyMatch(imagesByTourId::containsKey))) {
                return null;
            }
            files.addAll(batch);
        }
        if (files.isEmpty()) {
            return Map.of();
        }
        return files.stream()
                .filter(Objects::nonNull)
                .filter(file -> file.getTourId() != null && !file.getTourId().isBlank())
                .filter(file -> file.getUrl() != null && !file.getUrl().isBlank())
                .collect(Collectors.groupingBy(
                        TourFileResponse::getTourId,
                        Collectors.mapping(TourFileResponse::getUrl, Collectors.toList())));
    }

    private Map<String, List<String>> readSnapshot() {
        if (!Files.isRegularFile(snapshotFile)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(snapshotFile.toFile(), SNAPSHOT_TYPE);
        } catch (IOException | RuntimeException failure) {
            log.warn("Cannot read tour image snapshot {}: {}", snapshotFile, failure.toString());
            return Map.of();
        }
    }

    private void writeSnapshot(Map<String, List<String>> images) {
        try {
            Path directory = snapshotFile.toAbsolutePath().getParent();
            Files.createDirectories(directory);
            Path temporary = Files.createTempFile(directory, "tour-images", ".json");
            objectMapper.writeValue(temporary.toFile(), images);
            Files.move(temporary, snapshotFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException failure) {
            log.warn("Cannot persist tour image snapshot {}: {}", snapshotFile, failure.toString());
        }
    }
}
