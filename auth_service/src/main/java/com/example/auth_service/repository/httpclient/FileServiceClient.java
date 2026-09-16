package com.example.auth_service.repository.httpclient;

import com.example.auth_service.config.AuthenticationRequestInterceptor;
import com.example.auth_service.config.FeignConfiguration;
import com.example.auth_service.dto.FileInfo;
import com.example.auth_service.dto.request.ApiResponse;
import com.example.auth_service.dto.response.FileResponse;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.MediaType;

@FeignClient(name = "file-service", url = "http://localhost:8087", configuration = {
        AuthenticationRequestInterceptor.class,
        FeignConfiguration.class
})
public interface FileServiceClient {

    @GetMapping("/file/owner/{ownerId}")
    FileInfo getFileByOwnerId(@PathVariable("ownerId") String ownerId);

     @PostMapping(value = "/file/media/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
   FileResponse uploadFile(@RequestPart("file") MultipartFile file);

   @PostMapping(value = "/file/media/uploadAvt/{ownerId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
   FileResponse uploadAvt(
                   @PathVariable("ownerId") String ownerId,
                   @RequestPart("file") MultipartFile file);

}