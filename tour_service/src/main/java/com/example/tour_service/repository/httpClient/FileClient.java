package com.example.tour_service.repository.httpClient;

import java.util.List;

import com.example.tour_service.dto.response.FileResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.example.tour_service.config.AuthenticationRequestInterceptor;
import com.example.tour_service.dto.response.TourFileResponse;


@FeignClient(name = "file-service", url = "${app.services.file-base-url:http://localhost:8087}" ,configuration = AuthenticationRequestInterceptor.class
)
public interface FileClient {

    @GetMapping("/file/media/tours")
    List<TourFileResponse> getTourMedia(@RequestParam("tourIds") List<String> tourIds);

    @PostMapping(value = "/file/media/uploadTour/{tourId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    List<FileResponse> uploadMultipleFiles(
         @PathVariable("tourId") String tourId,
        @RequestPart("files") List<MultipartFile> files
    );

    @PostMapping(value = "/file/media/upload/location", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    FileResponse uploadLocationImg(
            @RequestParam(value = "locationId", required = false) Integer locationId,
            @RequestPart("file") MultipartFile file
    );



    @PostMapping(value = "/file/media/tour/{tourId}/edit", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    List<TourFileResponse> editTourImages(@PathVariable("tourId") String tourId,
            @RequestPart("changes") String removedUrlsJson,
            @RequestPart(value = "files", required = false) List<MultipartFile> files);

}
