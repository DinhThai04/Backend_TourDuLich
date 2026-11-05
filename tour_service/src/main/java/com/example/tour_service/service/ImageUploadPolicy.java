package com.example.tour_service.service;

import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.util.List;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

public final class ImageUploadPolicy {
    public static final int MAX_IMAGES = 10;
    public static final long MAX_BYTES = 5L * 1024 * 1024;
    public static final long MAX_AVATAR_BYTES = 2L * 1024 * 1024;
    public static final long MAX_REQUEST_BYTES = 25L * 1024 * 1024;
    public static final long MAX_PIXELS = 20_000_000L;
    // A decode holds ~4 bytes per pixel, so the pool is rationed in megapixels instead of in readers:
    // at most DECODE_PERMITS megapixels of decoded pixels are resident at once (~32 MB of small images,
    // or one 20 MP upload taking the whole pool). Nothing larger than a single accepted image fits.
    private static final int DECODE_PERMITS = 8;
    private static final java.util.concurrent.Semaphore DECODERS = new java.util.concurrent.Semaphore(DECODE_PERMITS);
    private ImageUploadPolicy() {}
    public static void validateAvatar(MultipartFile file) {
        if (file == null || file.getSize() > MAX_AVATAR_BYTES)
            throw new IllegalArgumentException("Avatar tối đa 2 MB");
        validate(List.of(file));
    }
    public static void validate(List<MultipartFile> files) {
        if (files == null) return;
        if (files.size() > MAX_IMAGES) throw new IllegalArgumentException("Mỗi lần chỉ được chọn tối đa 10 ảnh");
        long total = 0;
        for (var file : files) {
            if (file == null || file.isEmpty() || file.getSize() > MAX_BYTES)
                throw new IllegalArgumentException("Ảnh phải có dung lượng từ 1 byte đến 5 MB");
            total += file.getSize();
            if (total > MAX_REQUEST_BYTES) throw new IllegalArgumentException("Tổng ảnh mỗi lần tối đa 25 MB");
        }
        for (var file : files) {
            try (var input = file.getInputStream()) {
                byte[] bytes = input.readNBytes(12);
                String detected = bytes.length >= 8 && bytes[0] == (byte)137 && bytes[1] == 80 && bytes[2] == 78 && bytes[3] == 71
                        && bytes[4] == 13 && bytes[5] == 10 && bytes[6] == 26 && bytes[7] == 10 ? "image/png"
                        : bytes.length >= 3 && bytes[0] == (byte)255 && bytes[1] == (byte)216 && bytes[2] == (byte)255 ? "image/jpeg"
                        : bytes.length == 12 && bytes[0] == 82 && bytes[1] == 73 && bytes[2] == 70 && bytes[3] == 70
                        && bytes[8] == 87 && bytes[9] == 69 && bytes[10] == 66 && bytes[11] == 80 ? "image/webp" : "";
                if (detected.isEmpty() || !detected.equalsIgnoreCase(file.getContentType()))
                    throw new IllegalArgumentException("Chỉ nhận ảnh JPEG, PNG hoặc WebP đúng định dạng");
            } catch (IOException exception) { throw new IllegalArgumentException("Không đọc được file ảnh", exception); }
            try (var stream = new MemoryCacheImageInputStream(file.getInputStream())) {
                var readers = ImageIO.getImageReaders(stream);
                if (!readers.hasNext()) throw new IllegalArgumentException("Không giải mã được ảnh");
                var reader = readers.next();
                try {
                    reader.setInput(stream, false, true);
                    int width = reader.getWidth(0), height = reader.getHeight(0);
                    if (width <= 0 || height <= 0 || (long)width * height > MAX_PIXELS)
                        throw new IllegalArgumentException("Ảnh tối đa 20 triệu pixel");
                    if (reader.getNumImages(true) != 1) throw new IllegalArgumentException("Chỉ nhận ảnh tĩnh");
                    int permits = decodePermits(width, height);
                    if (!DECODERS.tryAcquire(permits)) throw new org.springframework.web.server.ResponseStatusException(
                            org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "Đang xử lý ảnh, vui lòng thử lại");
                    try {
                        var decoded = reader.read(0);
                        if (decoded == null) throw new IllegalArgumentException("Ảnh không hợp lệ");
                        decoded.flush();
                    } finally { DECODERS.release(permits); }
                } finally { reader.dispose(); }
            } catch (IOException exception) { throw new IllegalArgumentException("Ảnh bị hỏng hoặc không hợp lệ", exception); }
        }
    }
    /** Megapixels a decode may hold in memory at once; a whole 20 MP image therefore takes the entire pool. */
    public static int decodePermits(int width, int height) {
        long megapixels = ((long) width * height + 999_999L) / 1_000_000L;
        return (int) Math.min(DECODE_PERMITS, Math.max(1L, megapixels));
    }
}
