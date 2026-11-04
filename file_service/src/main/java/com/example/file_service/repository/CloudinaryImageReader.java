package com.example.file_service.repository;

import com.example.file_service.service.ImageUploadPolicy;
import java.io.*;
import java.net.*;

/** Bounded proxy for existing images in this Cloudinary account; no arbitrary remote fetch. */
public class CloudinaryImageReader {
    private static final java.util.concurrent.Semaphore READERS = new java.util.concurrent.Semaphore(2);
    public byte[] read(String url, String cloudName) throws IOException {
        URI uri = validateUrl(url, cloudName);
        if (!READERS.tryAcquire()) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "Đang tải ảnh, vui lòng thử lại");
        HttpURLConnection connection = null;
        try {
            connection = open(uri);
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(5000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "image/jpeg,image/png,image/webp");
            if (connection.getResponseCode() != 200) throw new IOException("Image origin unavailable");
            long size = connection.getContentLengthLong();
            String type = connection.getContentType();
            if (type == null || !java.util.Set.of("image/jpeg", "image/png", "image/webp").contains(type.split(";")[0].trim().toLowerCase(java.util.Locale.ROOT))
                    || size > ImageUploadPolicy.MAX_BYTES) throw new IOException("Invalid image response");
            try (var input = connection.getInputStream()) { return readBounded(input); }
        } finally { if (connection != null) connection.disconnect(); READERS.release(); }
    }
    protected HttpURLConnection open(URI uri) throws IOException { return (HttpURLConnection)uri.toURL().openConnection(); }
    public static URI validateUrl(String url, String cloudName) {
        try {
            URI uri = URI.create(url);
            String path = uri.getRawPath();
            if (cloudName == null || !cloudName.matches("[A-Za-z0-9_-]+") || !"https".equals(uri.getScheme())
                    || !"res.cloudinary.com".equals(uri.getHost()) || uri.getPort() != -1 || uri.getUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null || path == null
                    || !path.startsWith("/"+cloudName+"/image/upload/") || path.contains("%") || path.contains("\\")
                    || java.util.Arrays.asList(path.split("/", -1)).contains("..")
                    || java.util.Arrays.asList(path.split("/", -1)).contains(".")) throw new IllegalArgumentException();
            return uri;
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IllegalArgumentException("Chỉ tải ảnh HTTPS trong tài khoản Cloudinary đã cấu hình");
        }
    }
    static byte[] readBounded(InputStream input) throws IOException {
        var output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (System.nanoTime() > deadline || (long)output.size()+count > ImageUploadPolicy.MAX_BYTES)
                throw new IOException("Image download limit exceeded");
            output.write(buffer,0,count);
        }
        if (output.size()==0) throw new IOException("Empty image response");
        return output.toByteArray();
    }
}
