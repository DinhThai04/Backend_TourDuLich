package com.example.file_service.controller;

import java.io.IOException;
import org.springframework.security.access.prepost.PreAuthorize;
import java.util.List;

import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.example.file_service.dto.reponse.FileResponse;
import com.example.file_service.dto.reponse.TourFileResponse;
import com.example.file_service.service.FileService;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;

@RestController
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class FileController {
    FileService fileService;
    com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    com.example.file_service.config.DownloadThrottle downloadThrottle;

    @PreAuthorize("hasAnyAuthority('SGU_SUPER_ADMIN', 'PERM:TOURS_UPDATE', 'PERM:TOURS_DELETE')")
    @GetMapping("/media/tour-orphans")
    public org.springframework.data.domain.Page<FileService.DetachedImage> detachedImages(@RequestParam(defaultValue="0") int page) {
        return fileService.detachedImages(page);
    }

    @PreAuthorize("hasAnyAuthority('SGU_SUPER_ADMIN', 'PERM:TOURS_DELETE')")
    @DeleteMapping("/media/tour-orphans/{id}")
    public ResponseEntity<Void> deleteDetachedImage(@PathVariable String id) {
        fileService.permanentlyDeleteDetachedImage(id);
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("hasAnyAuthority('SGU_SUPER_ADMIN', 'PERM:TOURS_UPDATE', 'PERM:TOURS_DELETE')")
    @PostMapping(value = "/media/tour/{tourId}/edit", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<List<TourFileResponse>> editTourImages(@PathVariable String tourId,
            @RequestPart("changes") String changes,
            @RequestPart(value = "files", required = false) List<MultipartFile> files) throws IOException {
        if (changes.length() > 110000) throw new IllegalArgumentException("Danh sách ảnh quá dài");
        String[] removed;
        try { removed = objectMapper.readValue(changes, String[].class); }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalArgumentException("Danh sách ảnh không hợp lệ"); }
        return ResponseEntity.ok(fileService.editTourImages(tourId, removed == null ? List.of() : java.util.Arrays.asList(removed), files));
    }

    @PreAuthorize("hasAnyAuthority('SGU_SUPER_ADMIN', 'PERM:LOCATIONS_CREATE', 'PERM:LOCATIONS_UPDATE')")
    @PostMapping(value = "/media/upload/location", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<FileResponse> uploadLocationImg(
            @RequestParam(value = "locationId", required = false) Integer locationId,
            @RequestPart("file") MultipartFile file
    ) throws IOException {
        return ResponseEntity.ok(fileService.uploadFile(file, locationId));
    }

    @GetMapping("/media/download/{fileName}")
    ResponseEntity<Resource> downloadMedia(@PathVariable String fileName,
            jakarta.servlet.http.HttpServletRequest request) throws IOException {
        // Streaming stays unchanged; only a caller that exceeds its per-minute budget is turned away early.
        long retryAfter = downloadThrottle.reserve(request);
        if (retryAfter > 0) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter))
                    .build();
        }
        var fileData = fileService.download(fileName);

        String contentType = fileData.contentType();
        // Legacy seed rows have a NULL content type; never send an empty Content-Type header.
        return ResponseEntity.<Resource>ok()
                .header(HttpHeaders.CONTENT_TYPE,
                        contentType == null || contentType.isBlank()
                                ? MediaType.APPLICATION_OCTET_STREAM_VALUE
                                : contentType)
                .body(fileData.resource());
    }

    @GetMapping("/owner/{ownerId}")
    public ResponseEntity<com.example.file_service.dto.reponse.FileOwnerResponse> getFileByOwner(@PathVariable String ownerId) {
        return ResponseEntity.ok(fileService.getFileByOwnerId(ownerId));

    }

    @GetMapping("/media/tours")
    public ResponseEntity<List<TourFileResponse>> getAllMedia(@RequestParam("tourIds") List<String> tourIds) {
        return ResponseEntity.ok(fileService.getTourMedia(tourIds));
    }

    @PreAuthorize("hasAnyAuthority('SGU_SUPER_ADMIN', 'PERM:TOURS_CREATE', 'PERM:TOURS_UPDATE')")
    @PostMapping("/media/uploadTour/{tourId}")
    public ResponseEntity<List<FileResponse>> uploadMultipleFiles(
            @PathVariable("tourId") String tourId,
            @RequestPart("files") List<MultipartFile> files) throws IOException {
        return ResponseEntity.ok(fileService.uploadImageTour(tourId, files));
    }

  @PreAuthorize("hasAnyAuthority('SGU_SUPER_ADMIN', 'PERM:USERS_UPDATE') or #ownerId == authentication.name")
    @PostMapping("/media/uploadAvt/{ownerId}")
public ResponseEntity<FileResponse> uploadAvt(
        @PathVariable("ownerId") String ownerId,
        @RequestPart("file") MultipartFile file) throws IOException {

    FileResponse response = fileService.uploadAvt(ownerId, file);
    return ResponseEntity.ok(response);
}
}
