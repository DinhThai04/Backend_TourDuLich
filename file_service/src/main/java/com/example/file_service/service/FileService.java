package com.example.file_service.service;


import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import com.example.file_service.dto.FileInfo;
import com.example.file_service.dto.reponse.FileData;
import com.example.file_service.dto.reponse.FileResponse;
import com.example.file_service.dto.reponse.TourFileResponse;
import com.example.file_service.entity.FileMgmt;
import com.example.file_service.mapper.FileMgmtMapper;
import com.example.file_service.repository.FileMgmtRepository;
import com.example.file_service.repository.FileRepository;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import com.example.file_service.exception.AppException;
import com.example.file_service.exception.ErrorCode;

@Service
@Slf4j
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class FileService {
    /** Sentinels used by the cleanup page for files that no longer belong to a tour. */
    private static final String DETACHED_TOUR_ID = "null";
    private static final String UNASSIGNED_OWNER = "";
    private static final long MAX_DETACHED_IMAGES = 500;
    FileRepository fileRepository;
    FileMgmtRepository fileMgmtRepository;

    FileMgmtMapper fileMgmtMapper;
    org.springframework.jdbc.core.JdbcTemplate jdbc;
    TransactionTemplate transactions;

    public FileResponse uploadFile(MultipartFile file, Integer locationId) throws IOException {
        if (locationId != null && locationId < 1) throw new IllegalArgumentException("ID địa điểm không hợp lệ");
        requireDetachedQuota();
        ImageUploadPolicy.validate(List.of(file));

        // Upload stays outside the transaction: Cloudinary latency must never hold a DB connection or lock.
        var fileInfo = fileRepository.store(file);
        var fileMgmt = fileMgmtMapper.toFileMgmt(fileInfo);
        fileMgmt.setId(UUID.randomUUID().toString());
        fileMgmt.setOwnerId(UNASSIGNED_OWNER);
        fileMgmt.setTourId(DETACHED_TOUR_ID);
        fileMgmt.setLocationId(locationId);

        // Detaching the old image and linking the new one must commit together.
        var completion = new AtomicReference<Integer>();
        boolean linked = false;
        try {
            transactions.executeWithoutResult(status -> {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCompletion(int code) { completion.set(code); }
                });
                detachPreviousLocationImages(locationId);
                fileMgmtRepository.saveAndFlush(fileMgmt);
            });
            linked = true;
        } catch (org.springframework.dao.DataIntegrityViolationException concurrentEdit) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                    "Ảnh địa điểm đang được cập nhật ở nơi khác; hãy tải lại rồi thử lại", concurrentEdit);
        } finally {
            // An exception that leaves this method means the metadata can no longer commit; a status
            // the transaction already reported stays authoritative.
            if (!linked && completion.get() == null) completion.set(TransactionSynchronization.STATUS_ROLLED_BACK);
            cleanupUncommitted(completion.get(), List.of(fileMgmt), List.of(), "Location image");
        }
        return FileResponse.builder()
                .originalFileName(file.getOriginalFilename())
                .url(fileInfo.getUrl())
                .build();
    }

    /** Only the current image stays linked; older ones become visible to the orphan cleanup page. */
    private void detachPreviousLocationImages(Integer locationId) {
        if (locationId == null) return;
        var previous = fileMgmtRepository.findByLocationId(locationId);
        for (var image : previous) image.setLocationId(null);
        if (!previous.isEmpty()) {
            fileMgmtRepository.saveAll(previous);
            // Hibernate flushes inserts before updates, so the released key must land first.
            fileMgmtRepository.flush();
        }
    }

    /** Content uploads stop at a full cleanup backlog, so storage cannot grow without bound. */
    private void requireDetachedQuota() {
        if (fileMgmtRepository.countByTourIdAndOwnerIdAndLocationIdIsNullAndAvatarOwnerIdIsNull(
                DETACHED_TOUR_ID, UNASSIGNED_OWNER) >= MAX_DETACHED_IMAGES)
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                    "Đã có quá nhiều ảnh chờ dọn; hãy xóa ảnh mồ côi trước khi tải thêm");
    }

    public FileData download(String fileId) throws IOException {
        var fileMgmt = fileMgmtRepository.findById(fileId).orElseThrow(
                () -> new AppException(ErrorCode.FILE_NOT_FOUND));

        var resource = fileRepository.read(fileMgmt);

        return new FileData(fileMgmt.getContentType(), resource);
    }

    public com.example.file_service.dto.reponse.FileOwnerResponse getFileByOwnerId(String ownerId) {
        FileMgmt avatar = fileMgmtRepository.findByAvatarOwnerId(ownerId)
                .orElseThrow(() -> new AppException(ErrorCode.FILE_NOT_FOUND));
        return new com.example.file_service.dto.reponse.FileOwnerResponse(
                avatar.getUrl(), avatar.getContentType(), avatar.getSize());
    }

    /** Bounded by tour IDs on purpose: an unfiltered image dump must not be publicly reachable. */
    public List<TourFileResponse> getTourMedia(List<String> tourIds) {
        if (tourIds == null || tourIds.isEmpty() || tourIds.size() > 50)
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"Cần từ 1 đến 50 ID tour");
        java.util.Set<String> normalized = new java.util.TreeSet<>();
        for (String id : tourIds) {
            try {
                if (id == null || !id.matches("[0-9]{1,10}") || Integer.parseInt(id) <= 0) throw new IllegalArgumentException();
                normalized.add(String.valueOf(Integer.parseInt(id)));
            } catch (IllegalArgumentException invalid) {
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"ID tour không hợp lệ");
            }
        }
        return fileMgmtRepository.findByTourIdInOrderByIdAsc(List.copyOf(normalized)).stream()
                .map(file -> new TourFileResponse(file.getTourId(), file.getUrl())).toList();
    }

    public List<FileResponse> uploadImageTour(String tourId, List<MultipartFile> files) throws IOException {
        return editTourImages(tourId, List.of(), files).stream()
                .map(image -> FileResponse.builder().url(image.getUrl()).build()).toList();
    }


    public FileResponse uploadAvt(String ownerId, MultipartFile file) throws IOException {
        try { if(ownerId==null || !ownerId.matches("[1-9][0-9]{0,18}") || Long.parseLong(ownerId)<1)throw new IllegalArgumentException(); }
        catch(RuntimeException invalid){throw new IllegalArgumentException("ID tài khoản không hợp lệ");}
        ImageUploadPolicy.validateAvatar(file);
        // Detached avatars join the same cleanup backlog as content uploads.
        requireDetachedQuota();

        // Cloudinary upload stays outside the transaction: storage latency must never hold a DB connection or lock.
        var fileInfo=fileRepository.store(file);
        var fileMgmt=avatarRecord(ownerId,fileInfo);

        // A failed commit must not leave the new upload without metadata.
        var completion = new AtomicReference<Integer>();
        boolean stored = false;
        try {
            transactions.executeWithoutResult(status->{
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                    @Override public void afterCompletion(int code){ completion.set(code); }
                });
                lockAvatar(ownerId);
                var previous=fileMgmtRepository.findByAvatarOwnerId(ownerId);
                previous.ifPresent(old->{old.setAvatarOwnerId(null);old.setOwnerId(UNASSIGNED_OWNER);old.setTourId(DETACHED_TOUR_ID);fileMgmtRepository.save(old);});
                // Release the unique avatar key before inserting its replacement.
                fileMgmtRepository.flush();
                fileMgmtRepository.saveAndFlush(fileMgmt);
            });
            stored = true;
        } finally {
            if (!stored && completion.get() == null) completion.set(TransactionSynchronization.STATUS_ROLLED_BACK);
            cleanupUncommitted(completion.get(), List.of(fileMgmt), List.of(), "Avatar");
        }
        return FileResponse.builder().originalFileName(file.getOriginalFilename()).url(fileInfo.getUrl()).build();
    }

    private FileMgmt avatarRecord(String ownerId, FileInfo fileInfo) {
        var fileMgmt = fileMgmtMapper.toFileMgmt(fileInfo);
        fileMgmt.setId(UUID.randomUUID().toString());
        fileMgmt.setOwnerId(ownerId);
        fileMgmt.setTourId(DETACHED_TOUR_ID);
        fileMgmt.setAvatarOwnerId(ownerId);
        return fileMgmt;
    }

    private void lockAvatar(String ownerId) {
        jdbc.update("INSERT INTO avatar_media_lock(owner_id) VALUES (?) ON DUPLICATE KEY UPDATE owner_id=owner_id",ownerId);
        jdbc.queryForObject("SELECT owner_id FROM avatar_media_lock WHERE owner_id=? FOR UPDATE",String.class,ownerId);
    }

    /**
     * Compensates a transaction that did not commit. Runs after the transaction has finished, so a
     * slow provider never keeps a pooled connection. A null outcome means the block joined an outer
     * transaction that has not completed yet, so that transaction still owns the assets; an unknown
     * outcome is only reported, because the metadata may have committed and the asset is then
     * reachable through the cleanup page.
     */
    private void cleanupUncommitted(Integer completion, List<FileMgmt> uploaded, List<FileMgmt> superseded, String subject) {
        if (completion == null) return;
        if (completion == TransactionSynchronization.STATUS_ROLLED_BACK) {
            deleteUploaded(uploaded);
            deleteUploaded(superseded);
        } else if (completion == TransactionSynchronization.STATUS_UNKNOWN) {
            log.warn("{} commit outcome unknown; verify {} asset(s) before cleanup", subject, uploaded.size() + superseded.size());
        }
    }

    /** Edit gallery links atomically. Removed images remain in Cloudinary for manual cleanup. */
    public List<TourFileResponse> editTourImages(String tourId, List<String> removedUrls, List<MultipartFile> uploads) throws IOException {
        if (!isTourId(tourId))
            throw new IllegalArgumentException("ID tour không hợp lệ");
        var remove = removedUrls == null ? List.<String>of() : removedUrls;
        var files = uploads == null ? List.<MultipartFile>of() : uploads;
        if (remove.size() > 50 || remove.stream().anyMatch(url -> url == null || url.isBlank() || url.length() > 2048))
            throw new IllegalArgumentException("Danh sách ảnh không hợp lệ");
        ImageUploadPolicy.validate(files);

        var unique = new HashSet<>(remove);
        var previous = unique.isEmpty() ? List.<FileMgmt>of() : fileMgmtRepository.findByTourIdAndUrlIn(tourId, List.copyOf(unique));
        var found = previous.stream().map(FileMgmt::getUrl).collect(Collectors.toSet());
        if (!found.equals(unique)) throw new IllegalArgumentException("Ảnh đã thay đổi hoặc không thuộc tour đang sửa");
        var current = fileMgmtRepository.findByTourId(tourId);
        var existingFingerprints = current.stream().filter(image -> !unique.contains(image.getUrl()))
                .map(FileMgmt::getMd5Checksum).filter(Objects::nonNull).collect(Collectors.toSet());
        var pending = new LinkedHashMap<String, MultipartFile>();
        for (var upload : files) {
            // SHA-256 is stored in the existing checksum column; successful retries do not duplicate gallery images.
            String fingerprint = fingerprint(upload);
            if (!existingFingerprints.contains(fingerprint)) pending.putIfAbsent(fingerprint, upload);
        }
        if (!pending.isEmpty() && current.size() - previous.size() + pending.size() > ImageUploadPolicy.MAX_IMAGES)
            throw new IllegalArgumentException("Mỗi tour tối đa 10 ảnh; hãy gỡ bớt ảnh trước khi thêm");
        if (!pending.isEmpty()) requireDetachedQuota();

        var uploaded = new ArrayList<FileMgmt>();
        var superseded = new ArrayList<FileMgmt>();
        // A rolled-back transaction still owns its assets; an unknown commit outcome must not be
        // deleted, because the metadata may actually have committed.
        var completion = new AtomicReference<Integer>();
        boolean committedBlock = false;
        List<TourFileResponse> linked;
        try {
            // Upload to Cloudinary first: a slow provider must not hold a DB connection or the gallery lock.
            for (var entry : pending.entrySet()) {
                var upload = entry.getValue();
                if (upload == null || upload.isEmpty()) throw new IllegalArgumentException("File ảnh trống");
                var info = fileRepository.store(upload);
                var record = fileMgmtMapper.toFileMgmt(info);
                record.setId(UUID.randomUUID().toString());
                record.setOwnerId(UNASSIGNED_OWNER);
                record.setTourId(tourId);
                record.setMd5Checksum(entry.getKey());
                uploaded.add(record);
            }
            linked = transactions.execute(status -> {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCompletion(int code) { completion.set(code); }
                });
                return linkTourImages(tourId, unique, previous, current, uploaded, superseded);
            });
            committedBlock = true;
        } finally {
            if (!committedBlock && completion.get() == null) completion.set(TransactionSynchronization.STATUS_ROLLED_BACK);
            cleanupUncommitted(completion.get(), uploaded, superseded, "Tour image");
        }
        // A committed edit owns these deletes, so the storage call stays outside the locked section.
        deleteUploaded(superseded);
        return linked;
    }

    /** Short locked section: re-check the gallery, then link the already-uploaded files. */
    private List<TourFileResponse> linkTourImages(String tourId, Set<String> removedUrls,
            List<FileMgmt> previous, List<FileMgmt> current, List<FileMgmt> uploaded, List<FileMgmt> superseded) {
        // Separate lock row: locking the tour itself can deadlock a caller in tour_service's transaction.
        jdbc.update("INSERT INTO tour_media_lock (tour_id) VALUES (?) ON DUPLICATE KEY UPDATE tour_id=tour_id",tourId);
        jdbc.queryForObject("SELECT tour_id FROM tour_media_lock WHERE tour_id=? FOR UPDATE",String.class,tourId);
        var locked = fileMgmtRepository.findByTourId(tourId);
        if (locked.size() != current.size() || !urls(locked).equals(urls(current)))
            throw new IllegalArgumentException("Ảnh đã thay đổi, vui lòng tải lại");
        if (locked.size() - previous.size() + uploaded.size() > ImageUploadPolicy.MAX_IMAGES)
            throw new IllegalArgumentException("Mỗi tour tối đa 10 ảnh; hãy gỡ bớt ảnh trước khi thêm");
        // Another edit may have added an identical file while this request was uploading; the caller cleans it up.
        var lockedFingerprints = locked.stream().filter(image -> !removedUrls.contains(image.getUrl()))
                .map(FileMgmt::getMd5Checksum).filter(Objects::nonNull).collect(Collectors.toSet());
        for (var iterator = uploaded.iterator(); iterator.hasNext(); ) {
            var record = iterator.next();
            if (lockedFingerprints.contains(record.getMd5Checksum())) {
                superseded.add(record);
                iterator.remove();
            }
        }
        fileMgmtRepository.saveAll(uploaded);
        // Keep detached metadata so an admin can clean up Cloudinary explicitly later.
        for (var old : previous) { old.setTourId(DETACHED_TOUR_ID); old.setOwnerId(UNASSIGNED_OWNER); }
        fileMgmtRepository.saveAll(previous);
        fileMgmtRepository.flush();
        return getTourMedia(List.of(tourId));
    }

    private static String fingerprint(MultipartFile upload) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(upload.getBytes())); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        catch (IOException unreadable) { throw new IllegalArgumentException("Không đọc được file ảnh", unreadable); }
    }

    private static Set<String> urls(List<FileMgmt> images) {
        return images.stream().map(FileMgmt::getUrl).collect(Collectors.toSet());
    }

    private void deleteQuietly(FileMgmt image) {
        try { fileRepository.delete(image); }
        catch (RuntimeException cleanupFailed) { log.warn("Cloudinary cleanup required for public_id={}", image.getPath()); }
    }

    private void deleteUploaded(List<FileMgmt> images) {
        for (var image : images) {
            deleteQuietly(image);
        }
    }

    private boolean isTourId(String id) { return id != null && id.matches("[1-9][0-9]{0,8}"); }

    public org.springframework.data.domain.Page<DetachedImage> detachedImages(int page) {
        if (page < 0 || page > 100000) throw new IllegalArgumentException("Trang không hợp lệ");
        return fileMgmtRepository.findByTourIdAndOwnerIdAndLocationIdIsNullAndAvatarOwnerIdIsNull(DETACHED_TOUR_ID, UNASSIGNED_OWNER, org.springframework.data.domain.PageRequest.of(page, 15, org.springframework.data.domain.Sort.by("id")))
                .map(image -> new DetachedImage(image.getId(), image.getUrl(), image.getPath(), fileRepository.canDelete(image)));
    }
    public record DetachedImage(String id, String url, String publicId, boolean verified) {}

    public void permanentlyDeleteDetachedImage(String id) {
        var image = transactions.execute(status -> {
            var locked = fileMgmtRepository.lockById(id).orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ảnh; hãy tải lại danh sách"));
            if (!DETACHED_TOUR_ID.equals(locked.getTourId()) || !UNASSIGNED_OWNER.equals(locked.getOwnerId()) || locked.getLocationId() != null || locked.getAvatarOwnerId()!=null)
                throw new IllegalArgumentException("Phải gỡ ảnh khỏi tour trước khi xóa vĩnh viễn");
            if (!fileRepository.canDelete(locked))
                throw new IllegalArgumentException("Ảnh cũ chưa xác minh được public_id; hãy kiểm tra trực tiếp trên Cloudinary");
            var references = fileMgmtRepository.lockAssetReferences(locked.getPath(), locked.getUrl());
            if (references.stream().anyMatch(reference -> !DETACHED_TOUR_ID.equals(reference.getTourId()) || !UNASSIGNED_OWNER.equals(reference.getOwnerId()) || reference.getLocationId() != null || reference.getAvatarOwnerId()!=null)
                    || fileMgmtRepository.countDeliveryUrlReferences(locked.getUrl()) > 0)
                throw new IllegalArgumentException("Ảnh còn được tham chiếu ở nơi khác; chưa thể xóa Cloudinary");
            fileMgmtRepository.deleteAll(references.isEmpty() ? List.of(locked) : references);
            fileMgmtRepository.flush();
            return locked;
        });

        // Delete Cloudinary only after the DB rows are committed; a failed commit must not lose the image.
        try { fileRepository.delete(image); }
        catch (RuntimeException failed) { log.warn("Cloudinary image remains after DB delete for public_id={}", image.getPath()); }
    }

}
