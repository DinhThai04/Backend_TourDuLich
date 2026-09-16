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
        // Upload lên Cloudinary
        Map uploadResult = cloudinary.uploader().upload(
                file.getBytes(),
                ObjectUtils.asMap(
                        "public_id", UUID.randomUUID().toString(),
                        "resource_type", "auto" // auto: ảnh/video/pdf đều được
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
        byte[] data = new java.net.URL(fileMgmt.getUrl()).openStream().readAllBytes();
        return new ByteArrayResource(data);
    }


    public void delete(FileMgmt fileMgmt) {
        try {
            String publicId = extractPublicId(fileMgmt.getUrl());
            cloudinary.uploader().destroy(publicId, ObjectUtils.emptyMap());
        } catch (IOException e) {
            throw new RuntimeException("Không thể xóa file khỏi Cloudinary", e);
        }
    }
    
    private String extractPublicId(String url) {
        if (url == null || !url.contains("/upload/"))
            return null;
        String[] parts = url.split("/upload/");
        String publicIdPart = parts[1];
        return publicIdPart.substring(0, publicIdPart.lastIndexOf('.')); // bỏ phần đuôi .jpg/.png
    }

}
