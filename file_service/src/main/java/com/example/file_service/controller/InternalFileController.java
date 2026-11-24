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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/internal/file/files")
@RequiredArgsConstructor
public class InternalFileController {

    private final FileService fileService;
    private final FileMgmtRepository fileMgmtRepository;
    private final FileReferenceRepository fileReferenceRepository;

    @PostMapping("/lock-avatar/{ownerId}")
    public ResponseEntity<LockAvatarResponse> lockAvatar(
            @PathVariable String ownerId,
            @Valid @RequestBody LockAvatarRequest request) {
        LockAvatarResponse response = fileService.lockAvatar(ownerId, request.getFileId().toString());
        
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
        fileService.cleanupReferences(ownerId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/detach-tour-gallery/{tourId}")
    public ResponseEntity<Void> detachTourGallery(@PathVariable String tourId,
            @RequestHeader("X-Event-Id") String eventId) {
        fileService.detachTourGallery(tourId, eventId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/detach-account-media/{ownerId}")
    public ResponseEntity<Void> detachAccountMedia(@PathVariable String ownerId,
            @RequestHeader("X-Event-Id") String eventId) {
        fileService.detachAccountMedia(ownerId, eventId);
        return ResponseEntity.noContent().build();
    }
}
