package com.example.tour_service.repository.httpClient;

import java.util.List;

import com.example.tour_service.dto.response.ApiResponse;
import com.example.tour_service.dto.response.LocationFileResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.example.tour_service.config.AuthenticationRequestInterceptor;
import com.example.tour_service.dto.response.TourFileResponse;


@FeignClient(name = "file-service", url = "http://localhost:8087" ,configuration = AuthenticationRequestInterceptor.class    
)
public interface FileClient {

    @GetMapping("/file/media/tours")
    List<TourFileResponse> getAllMedia();

    @PostMapping(value = "file/media/uploadTour/{tourId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    List<TourFileResponse> uploadMultipleFiles(
         @PathVariable("tourId") String tourId,
        @RequestPart("files") List<MultipartFile> files
    );

    @PostMapping(value = "file/media/upload/location", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    LocationFileResponse uploadLocationImg(
            @RequestParam(value = "locationId", required = false) Integer locationId,
            @RequestPart("file") MultipartFile file
    );



    @PostMapping(value = "file/media/tour", consumes = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<List<String>> deleteMultipleTourImagesByUrl(@RequestBody List<String> urls);

}