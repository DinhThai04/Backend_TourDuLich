package com.example.file_service.service;


import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import com.example.file_service.dto.reponse.FileData;
import com.example.file_service.dto.reponse.FileResponse;
import com.example.file_service.dto.reponse.TourFileResponse;
import com.example.file_service.entity.FileMgmt;
import com.example.file_service.mapper.FileMgmtMapper;
import com.example.file_service.repository.FileMgmtRepository;
import com.example.file_service.repository.FileRepository;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

import com.example.file_service.exception.AppException;
import com.example.file_service.exception.ErrorCode;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class FileService {
    FileRepository fileRepository;
    FileMgmtRepository fileMgmtRepository;

    FileMgmtMapper fileMgmtMapper;

    public FileResponse uploadFile(MultipartFile file) throws IOException {
        var fileInfo = fileRepository.store(file);
        var fileMgmt = fileMgmtMapper.toFileMgmt(fileInfo);
        fileMgmt.setId(UUID.randomUUID().toString());
        String userId = SecurityContextHolder.getContext().getAuthentication().getName();
        fileMgmt.setOwnerId(userId);
        fileMgmt.setTourId("null");
        fileMgmtRepository.save(fileMgmt);
        return FileResponse.builder()
            .originalFileName(file.getOriginalFilename())
            .url(fileInfo.getUrl())
            .build();
    }
    public FileResponse uploadFile(MultipartFile file, Integer locationId) throws IOException {
        var fileInfo = fileRepository.store(file);
        var fileMgmt = fileMgmtMapper.toFileMgmt(fileInfo);
        fileMgmt.setOwnerId("null");
        fileMgmt.setTourId("null");
        fileMgmt.setLocationId(locationId);
        fileMgmtRepository.save(fileMgmt);
        return FileResponse.builder()
                .originalFileName(file.getOriginalFilename())
                .url(fileInfo.getUrl())
                .build();
    }

    public FileData download(String fileId) throws IOException {
        var fileMgmt = fileMgmtRepository.findById(fileId).orElseThrow(
                () -> new AppException(ErrorCode.FILE_NOT_FOUND));

        var resource = fileRepository.read(fileMgmt);

        return new FileData(fileMgmt.getContentType(), resource);
    }

    public FileMgmt getFileByOwnerId(String ownerId) {
        return fileMgmtRepository.findByOwnerId(ownerId)
                .orElseThrow(() -> new AppException(ErrorCode.FILE_NOT_FOUND));
    }

    public List<TourFileResponse> getAllTourIdsAndUrls() {
        return fileMgmtRepository.findAll().stream()
                .map(file -> new TourFileResponse(file.getTourId(), file.getUrl()))
                .toList();
    }

    public List<FileResponse> uploadImageTour(String tourId, List<MultipartFile> files) throws IOException {
        String userId = SecurityContextHolder.getContext().getAuthentication().getName();
        List<FileResponse> responses = new java.util.ArrayList<>();

        for (MultipartFile file : files) {
            // Upload Cloudinary
            var fileInfo = fileRepository.store(file);

            // Map sang entity
            var fileMgmt = fileMgmtMapper.toFileMgmt(fileInfo);
            fileMgmt.setId(UUID.randomUUID().toString());
            fileMgmt.setOwnerId("");
            fileMgmt.setTourId(tourId);
            fileMgmtRepository.save(fileMgmt);
            responses.add(FileResponse.builder()
                    .originalFileName(file.getOriginalFilename())
                    .url(fileInfo.getUrl())
                    .build());
        }

        return responses;
    }


    public FileResponse uploadAvt(String ownerId, MultipartFile file) throws IOException {


        // --- 2. Kiểm tra có avatar cũ không ---
        var existingFileOpt = fileMgmtRepository.findByOwnerId(ownerId);
        existingFileOpt.ifPresent(oldFile -> {
            try {
                // Xóa file cũ trong storage (Cloudinary/S3/Local)
                fileRepository.delete(oldFile);
                // Xóa record trong DB
                fileMgmtRepository.delete(oldFile);
            } catch (Exception e) {
                throw new AppException(ErrorCode.FILE_NOT_FOUND);
            }
        });

        // --- 3. Upload file mới ---
        var fileInfo = fileRepository.store(file);

        // --- 4. Lưu metadata ---
        var fileMgmt = fileMgmtMapper.toFileMgmt(fileInfo);
        fileMgmt.setId(UUID.randomUUID().toString());
        fileMgmt.setOwnerId(ownerId);
        fileMgmt.setTourId("null");
        fileMgmtRepository.save(fileMgmt);

        // --- 5. Trả về phản hồi ---
        return FileResponse.builder()
                .originalFileName(file.getOriginalFilename())
                .url(fileInfo.getUrl())
                .build();
    }

    public void deleteMultipleTourImagesByUrl(List<String> urls) {
        for (String url : urls) {
            var file = fileMgmtRepository.findByUrl(url)
                    .orElseThrow(() -> new AppException(ErrorCode.FILE_NOT_FOUND));

            fileRepository.delete(file);
            fileMgmtRepository.delete(file);
        }
    }

}