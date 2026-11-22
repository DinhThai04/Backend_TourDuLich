package com.example.tour_service.repository.httpClient;

import com.example.tour_service.config.AuthenticationRequestInterceptor;
import com.example.tour_service.config.FileInternalFeignConfig;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@FeignClient(name = "file-service-internal", url = "${app.services.file-base-url:http://localhost:8087}", configuration = {AuthenticationRequestInterceptor.class, FileInternalFeignConfig.class})
public interface FileInternalClient {

    @PostMapping(value = "/file/internal/file/files/lock-avatar/{ownerId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> lockAvatar(
            @PathVariable("ownerId") String ownerId,
            @RequestBody LockAvatarRequest request);

    @GetMapping("/file/internal/file/files/{fileId}/verify")
    Map<String, Object> verifyFile(@PathVariable("fileId") String fileId);

    @DeleteMapping("/file/internal/file/files/cleanup-refs/{ownerId}")
    void cleanupRefs(@PathVariable("ownerId") String ownerId);

    record LockAvatarRequest(
            java.util.UUID fileId,
            String url,
            String contentType,
            long size
    ) {}
}