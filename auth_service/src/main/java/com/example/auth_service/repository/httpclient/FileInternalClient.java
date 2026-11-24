package com.example.auth_service.repository.httpclient;

import com.example.auth_service.config.FileInternalFeignConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@FeignClient(name = "file-service-internal", url = "${app.services.file-base-url:http://localhost:8087}", configuration = FileInternalFeignConfiguration.class)
public interface FileInternalClient {

    @PostMapping(value = "/file/internal/file/files/lock-avatar/{ownerId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> lockAvatar(
            @PathVariable("ownerId") String ownerId,
            @RequestBody LockAvatarRequest request);

    @GetMapping("/file/internal/file/files/{fileId}/verify")
    Map<String, Object> verifyFile(@PathVariable("fileId") String fileId);

    @DeleteMapping("/file/internal/file/files/cleanup-refs/{ownerId}")
    void cleanupRefs(@PathVariable("ownerId") String ownerId);

    @DeleteMapping("/file/internal/file/files/detach-account-media/{ownerId}")
    void detachAccountMedia(@PathVariable("ownerId") String ownerId,
            @RequestHeader("X-Event-Id") String eventId);

    record LockAvatarRequest(
            java.util.UUID fileId,
            String url,
            String contentType,
            long size
    ) {}
}
