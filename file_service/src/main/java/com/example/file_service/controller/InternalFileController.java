package com.example.file_service.controller;

import com.example.file_service.dto.internal.LockAvatarRequest;
import com.example.file_service.dto.internal.LockAvatarResponse;
import com.example.file_service.dto.internal.VerifyFileResponse;
import com.example.file_service.entity.FileMgmt;
import com.example.file_service.entity.FileReference;
import com.example.file_service.repository.FileMgmtRepository;
import com.example.file_service.repository.FileReferenceRepository;
import com.example.file_service.service.FileService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/internal/file/files")
@RequiredArgsConstructor
public class InternalFileController {

    private final FileMgmtRepository fileMgmtRepository;
    private final FileReferenceRepository fileReferenceRepository;
    private final FileService fileService;

    @PostMapping("/lock-avatar/{ownerId}")
    public ResponseEntity<LockAvatarResponse> lockAvatar(
            @PathVariable String ownerId,
            @Valid @RequestBody LockAvatarRequest request) {
        
        Optional<FileMgmt> existingLock = fileMgmtRepository.findByAvatarOwnerId(ownerId);
        if (existingLock.isPresent()) {
            throw new IllegalStateException("Avatar already locked for owner: " + ownerId);
        }
        
        Optional<FileMgmt> fileOpt = fileMgmtRepository.findById(request.getFileId().toString());
        if (fileOpt.isEmpty()) {
            throw new IllegalArgumentException("File not found: " + request.getFileId());
        }
        
        FileMgmt fileMgmt = fileOpt.get();
        fileMgmt.setAvatarOwnerId(ownerId);
        fileMgmtRepository.saveAndFlush(fileMgmt);
        
        FileReference reference = FileReference.builder()
                .fileId(request.getFileId().toString())
                .ownerType("AVATAR")
                .ownerId(ownerId)
                .createdAt(LocalDateTime.now())
                .build();
        fileReferenceRepository.save(reference);
        
        LockAvatarResponse response = LockAvatarResponse.builder()
                .fileId(request.getFileId())
                .avatarOwnerId(ownerId)
                .url(request.getUrl())
                .locked(true)
                .build();
        
        return ResponseEntity.created(URI.create("/internal/file/files/lock-avatar/" + ownerId))
                .body(response);
    }

    @GetMapping("/{fileId}/verify")
    public ResponseEntity<VerifyFileResponse> verifyFile(@PathVariable String fileId) {
        Optional<FileMgmt> fileOpt = fileMgmtRepository.findById(fileId);
        if (fileOpt.isEmpty()) {
            return ResponseEntity.ok(VerifyFileResponse.builder()
                    .fileId(UUID.fromString(fileId))
                    .exists(false)
                    .build());
        }
        
        FileMgmt fileMgmt = fileOpt.get();
        List<FileReference> references = fileReferenceRepository.findByFileId(fileId);
        
        String ownerType = "UNKNOWN";
        String ownerId = "";
        boolean canDelete = false;
        
        if (!references.isEmpty()) {
            FileReference ref = references.get(0);
            ownerType = ref.getOwnerType();
            ownerId = ref.getOwnerId();
            canDelete = "AVATAR".equals(ownerType) || "TOUR".equals(ownerType);
        } else if (fileMgmt.getAvatarOwnerId() != null) {
            ownerType = "AVATAR";
            ownerId = fileMgmt.getAvatarOwnerId();
            canDelete = true;
        } else if (!"null".equals(fileMgmt.getTourId())) {
            ownerType = "TOUR";
            ownerId = fileMgmt.getTourId();
            canDelete = true;
        }
        
        VerifyFileResponse response = VerifyFileResponse.builder()
                .fileId(UUID.fromString(fileId))
                .exists(true)
                .ownerType(ownerType)
                .ownerId(ownerId)
                .url(fileMgmt.getUrl())
                .canDelete(canDelete)
                .build();
        
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/cleanup-refs/{ownerId}")
    public ResponseEntity<Void> cleanupRefs(@PathVariable String ownerId) {
        int deletedAvatars = fileReferenceRepository.deleteByOwnerTypeAndOwnerId("AVATAR", ownerId);
        int deletedTours = fileReferenceRepository.deleteByOwnerTypeAndOwnerId("TOUR", ownerId);
        int deletedBookings = fileReferenceRepository.deleteByOwnerTypeAndOwnerId("BOOKING", ownerId);
        
        int totalDeleted = deletedAvatars + deletedTours + deletedBookings;
        
        if (totalDeleted > 0) {
            fileMgmtRepository.findByAvatarOwnerId(ownerId).ifPresent(file -> {
                file.setAvatarOwnerId(null);
                fileMgmtRepository.saveAndFlush(file);
            });
        }
        
        return ResponseEntity.noContent().build();
    }
}