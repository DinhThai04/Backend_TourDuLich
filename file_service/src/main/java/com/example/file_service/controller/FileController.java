package com.example.file_service.controller;

import java.io.IOException;
import java.util.List;

import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.example.file_service.dto.reponse.FileResponse;
import com.example.file_service.dto.reponse.TourFileResponse;
import com.example.file_service.dto.request.ApiResponse;
import com.example.file_service.entity.FileMgmt;
import com.example.file_service.service.FileService;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;

@RestController
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class FileController {
    FileService fileService;

    @PostMapping(value = "/media/upload")
    public ResponseEntity<FileResponse> uploadMedia(@RequestPart("file") MultipartFile file) throws IOException {
        System.out.println("📂 Nhận file: " + file.getOriginalFilename());
        return ResponseEntity.ok(fileService.uploadFile(file));
    }


    @PostMapping(value = "/media/upload/location", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<FileResponse> uploadLocationImg(
            @RequestParam(value = "locationId", required = false) Integer locationId,
            @RequestPart("file") MultipartFile file
    ) throws IOException {
        System.out.println("📂 Upload Location Img - File: " + file.getOriginalFilename() + ", LocID: " + locationId);
        return ResponseEntity.ok(fileService.uploadFile(file, locationId));
    }

    @GetMapping("/media/download/{fileName}")
    ResponseEntity<Resource> downloadMedia(@PathVariable String fileName) throws IOException {
        var fileData = fileService.download(fileName);

        return ResponseEntity.<Resource>ok()
                .header(HttpHeaders.CONTENT_TYPE, fileData.contentType())
                .body(fileData.resource());
    }

    @GetMapping("/owner/{ownerId}")
    public ResponseEntity<FileMgmt> getFileByOwner(@PathVariable String ownerId) {
        return ResponseEntity.ok(fileService.getFileByOwnerId(ownerId));

    }

    @GetMapping("/media/tours")
    public ResponseEntity<List<TourFileResponse>> getAllMedia() {
        return ResponseEntity.ok(fileService.getAllTourIdsAndUrls());
    }

    @PostMapping("/media/uploadTour/{tourId}")
    public ResponseEntity<List<FileResponse>> uploadMultipleFiles(
            @PathVariable("tourId") String tourId,
            @RequestPart("files") List<MultipartFile> files) throws IOException {
        return ResponseEntity.ok(fileService.uploadImageTour(tourId, files));
    }

  @PostMapping("/media/uploadAvt/{ownerId}")
public ResponseEntity<FileResponse> uploadAvt(
        @PathVariable("ownerId") String ownerId,
        @RequestPart("file") MultipartFile file) throws IOException {

    FileResponse response = fileService.uploadAvt(ownerId, file);
    return ResponseEntity.ok(response);
}

    @PostMapping("/media/tour")
    public ResponseEntity<ApiResponse<List<String>>> deleteMultipleTourImagesByUrl(
            @RequestBody List<String> urls) {

        fileService.deleteMultipleTourImagesByUrl(urls);

        return ResponseEntity.ok(
                ApiResponse.<List<String>>builder()
                        .message("Đã xóa " + urls.size() + " ảnh")
                        .result(urls)
                        .build()
        );
    }


}
