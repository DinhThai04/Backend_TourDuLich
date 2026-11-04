package com.example.file_service.repository;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import com.example.file_service.dto.FileInfo;
import com.example.file_service.entity.FileMgmt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Repository;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Repository
public class FileRepository {

    @Autowired
    private Cloudinary cloudinary;

    public FileInfo store(MultipartFile file) throws IOException {
        com.example.file_service.service.ImageUploadPolicy.validate(java.util.List.of(file));
        // Upload lên Cloudinary
        Map uploadResult = cloudinary.uploader().upload(
                file.getBytes(),
                ObjectUtils.asMap(
                        "public_id", UUID.randomUUID().toString(),
                        "resource_type", "image"
                ));

        return FileInfo.builder()
                .name(Objects.toString(uploadResult.get("original_filename"), "unknown"))
                .size(file.getSize())
                .contentType(file.getContentType())
                .path(Objects.toString(uploadResult.get("public_id"), ""))
                .url(Objects.toString(uploadResult.get("secure_url"), ""))
                .build();
    }

    public Resource read(FileMgmt fileMgmt) throws IOException {
        // Cloudinary không cần đọc file từ local.
        // Nếu bạn muốn tải file từ URL của Cloudinary:
        byte[] data = new CloudinaryImageReader().read(fileMgmt.getUrl(), cloudinary.config.cloudName);
        return new ByteArrayResource(data);
    }


    public void delete(FileMgmt fileMgmt) {
        if (!canDelete(fileMgmt)) throw new IllegalArgumentException("Chưa xác minh được public_id của ảnh trong tài khoản Cloudinary này; cần kiểm tra thủ công");
        try {
            Map result = cloudinary.uploader().destroy(fileMgmt.getPath(), ObjectUtils.asMap("resource_type", "image", "invalidate", true));
            if (!java.util.Set.of("ok", "not found").contains(Objects.toString(result.get("result"), "")))
                throw new com.example.file_service.exception.MediaStorageException("Cloudinary chưa xác nhận xóa ảnh; dữ liệu ảnh được giữ lại để thử lại");
        } catch (IOException e) {
            throw new com.example.file_service.exception.MediaStorageException("Chưa xóa được ảnh trên Cloudinary; dữ liệu ảnh được giữ lại để thử lại", e);
        }
    }
    
    public boolean canDelete(FileMgmt file) {
        if (file.getPath() == null || file.getPath().isBlank() || file.getUrl() == null) return false;
        try {
            var uri = java.net.URI.create(file.getUrl());
            String expected = "/" + java.util.regex.Pattern.quote(cloudinary.config.cloudName) + "/image/upload/v[0-9]+/"
                    + java.util.regex.Pattern.quote(file.getPath()) + "\\.[A-Za-z0-9]+";
            return "https".equals(uri.getScheme()) && "res.cloudinary.com".equals(uri.getHost())
                    && uri.getRawPath().matches(expected) && uri.getUserInfo() == null && uri.getPort() == -1;
        } catch (IllegalArgumentException invalid) { return false; }
    }

}
