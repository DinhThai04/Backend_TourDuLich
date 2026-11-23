package com.example.file_service;

import com.example.file_service.dto.FileInfo;
import com.example.file_service.entity.FileMgmt;
import com.example.file_service.entity.FileReference;
import com.example.file_service.mapper.FileMgmtMapperImpl;
import com.example.file_service.repository.*;
import com.example.file_service.service.FileService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase(replace=org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE)
@DataJpaTest(properties={"spring.datasource.url=jdbc:h2:mem:gallery_persistence;MODE=MySQL;DB_CLOSE_DELAY=-1","spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=","spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"})
@Import({FileService.class, FileMgmtMapperImpl.class})
// Transaction-bound upload cleanup fires on the test rollback, after Mockito has reset the shared mock.
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class GalleryPersistenceTest {
    @MockBean com.example.file_service.service.AvatarWritePolicy avatarAccess;
    @Autowired FileService service;
    @Autowired FileMgmtRepository records;
    @Autowired FileReferenceRepository references;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @MockBean FileRepository storage;
    @org.junit.jupiter.api.BeforeEach void existingTours() {
        references.deleteAll();
        records.deleteAll();
        em.createNativeQuery("CREATE TABLE IF NOT EXISTS file_inbox(consumer VARCHAR(120),event_id VARCHAR(36),event_type VARCHAR(120),received_at TIMESTAMP,processed_at TIMESTAMP,PRIMARY KEY(consumer,event_id))").executeUpdate();
        em.createNativeQuery("DELETE FROM file_inbox").executeUpdate();
        em.createNativeQuery("DELETE FROM tour_media_lock").executeUpdate();
        em.createNativeQuery("CREATE TABLE IF NOT EXISTS tour (id INT PRIMARY KEY)").executeUpdate();
        em.createNativeQuery("MERGE INTO tour (id) KEY(id) VALUES (1),(2)").executeUpdate();
    }
    @Test void galleryUpdatePersistsAndPreservesSharedImagesLinkedToAnotherTour() throws Exception {
        records.saveAll(List.of(record("1","shared.jpg"),record("2","shared.jpg"),record("1","keep.jpg")));
        var file=new MockMultipartFile("files","new.png","image/png",validPng());
        when(storage.store(file)).thenReturn(FileInfo.builder().url("new.jpg").path("new-public-id").name("new.png").build());
        var response=service.editTourImages("1",List.of("shared.jpg"),List.of(file));
        assertEquals(Set.of("new.jpg","keep.jpg"),response.stream().map(x->x.getUrl()).collect(java.util.stream.Collectors.toSet()));
        em.flush();em.clear();
        assertEquals(Set.of("new.jpg","keep.jpg"),records.findByTourId("1").stream().map(FileMgmt::getUrl).collect(java.util.stream.Collectors.toSet()));
        assertEquals(List.of("shared.jpg"),records.findByTourId("2").stream().map(FileMgmt::getUrl).toList());
        assertEquals(1,records.findByTourId("null").size());
        var activeTourFiles = records.findByTourId("1");
        var activeFileIds = activeTourFiles.stream().map(FileMgmt::getId).collect(java.util.stream.Collectors.toSet());
        var referencedFileIds = references.findByOwnerTypeAndOwnerId("TOUR", "1").stream()
                .map(FileReference::getFileId).collect(java.util.stream.Collectors.toSet());
        assertEquals(activeFileIds, referencedFileIds, () -> "active=" + activeTourFiles.stream()
                .map(image -> image.getId() + ":" + image.getUrl()).toList() + ", references="
                + references.findByOwnerTypeAndOwnerId("TOUR", "1").stream()
                        .map(reference -> reference.getFileId() + ":" + records.findById(reference.getFileId())
                                .map(FileMgmt::getUrl).orElse("missing"))
                        .toList());
        verify(storage,never()).delete(any());
    }
    @Test void tourGalleryDetachIsTransactionalAndSafeToDeliverMoreThanOnce() {
        records.saveAll(List.of(record("1", "one.jpg"), record("1", "two.jpg"), record("2", "keep.jpg")));
        references.save(FileReference.builder().fileId("file-1").ownerType("TOUR")
                .ownerId("1").createdAt(java.time.LocalDateTime.now()).build());
        references.save(FileReference.builder().fileId("file-2").ownerType("TOUR")
                .ownerId("2").createdAt(java.time.LocalDateTime.now()).build());

        service.detachTourGallery("1");
        service.detachTourGallery("1");
        em.flush();

        assertEquals(2, records.findByTourId("null").size());
        assertEquals(List.of("keep.jpg"), records.findByTourId("2").stream().map(FileMgmt::getUrl).toList());
        assertFalse(references.existsByFileIdAndOwnerTypeAndOwnerId("file-1", "TOUR", "1"));
        assertTrue(references.existsByFileIdAndOwnerTypeAndOwnerId("file-2", "TOUR", "2"));
    }
    @Test void tourOutboxEventIsDeduplicatedInFileInboxWithDetach() {
        records.saveAll(List.of(record("1", "one.jpg"), record("1", "two.jpg")));
        String eventId = UUID.randomUUID().toString();

        service.detachTourGallery("1", eventId);
        service.detachTourGallery("1", eventId);
        em.flush();

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM file_inbox WHERE consumer='file.tour.gallery.detach.v1' AND event_id=? AND processed_at IS NOT NULL", Integer.class, eventId));
        assertEquals(2, records.findByTourId("null").size());
    }
    @Test void fileReferenceBulkDeleteByFileIdExecutesAsADelete() {
        references.save(FileReference.builder().fileId("file-1").ownerType("TOUR")
                .ownerId("1").createdAt(java.time.LocalDateTime.now()).build());

        assertEquals(1, references.deleteByFileId("file-1"));
        assertTrue(references.findByFileId("file-1").isEmpty());
    }
    @Test void locationAndAvatarWritesPersistReferencesWithTheStoredFileIds() throws Exception {
        when(storage.store(any())).thenReturn(
                FileInfo.builder().url("location.jpg").path("location-public-id").build(),
                FileInfo.builder().url("avatar.jpg").path("avatar-public-id").build(),
                FileInfo.builder().url("avatar-new.jpg").path("avatar-new-public-id").build());
        var upload = new MockMultipartFile("file", "image.png", "image/png", validPng());

        service.uploadFile(upload, 17);
        var locationFile = records.findByLocationId(17).getFirst();
        var firstAvatarUpload = service.uploadAvt("21", upload);
        var firstAvatarFile = records.findByAvatarOwnerId("21").orElseThrow();
        var secondAvatarUpload = service.uploadAvt("21", upload);

        assertEquals(1L, firstAvatarUpload.getAvatarVersion());
        assertEquals(2L, secondAvatarUpload.getAvatarVersion());
        assertTrue(references.existsByFileIdAndOwnerTypeAndOwnerId(locationFile.getId(), "LOCATION", "17"));
        assertFalse(references.existsByFileIdAndOwnerTypeAndOwnerId(firstAvatarFile.getId(), "AVATAR", "21"));
        assertTrue(references.existsByFileIdAndOwnerTypeAndOwnerId(
                records.findByAvatarOwnerId("21").orElseThrow().getId(), "AVATAR", "21"));
    }
    @Test void internalAvatarLockAlsoAdvancesTheFileOwnedRevision() {
        var uploaded = records.saveAndFlush(FileMgmt.builder().ownerId("").tourId("null").url("locked-avatar.jpg").build());

        var response = service.lockAvatar("31", uploaded.getId());

        assertEquals(1L, response.getAvatarVersion());
        assertEquals("locked-avatar.jpg", response.getUrl());
        assertTrue(references.existsByFileIdAndOwnerTypeAndOwnerId(uploaded.getId(), "AVATAR", "31"));
    }
    @Test void deletedTourTombstoneRejectsAStaleGalleryWrite() {
        records.save(record("1", "before-delete.jpg"));

        service.detachTourGallery("1");

        var failure = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.editTourImages("1", List.of(), List.of()));
        assertEquals(org.springframework.http.HttpStatus.NOT_FOUND, failure.getStatusCode());
        assertEquals(1, records.findByTourId("null").size());
    }
    private FileMgmt record(String tour,String url) {
        return FileMgmt.builder().tourId(tour).ownerId("").url(url).build();
    }
    @Test void sharedDetachedAssetsAreRetainedUntilEveryTourUnlinksThem() throws Exception {
        em.createNativeQuery("CREATE TABLE IF NOT EXISTS location (id INT PRIMARY KEY, img VARCHAR(2048))").executeUpdate();
        em.createNativeQuery("CREATE TABLE IF NOT EXISTS users (id INT PRIMARY KEY, avatar VARCHAR(2048))").executeUpdate();
        em.createNativeQuery("CREATE TABLE IF NOT EXISTS tour_image_ids (tour_id INT, image_id VARCHAR(2048))").executeUpdate();
        records.saveAll(List.of(record("1","shared.jpg"),record("2","shared.jpg")));
        service.editTourImages("1",List.of("shared.jpg"),List.of());em.flush();
        var detached=records.findByTourId("null").getFirst();
        when(storage.canDelete(any())).thenReturn(true);
        assertThrows(IllegalArgumentException.class,()->service.permanentlyDeleteDetachedImage(detached.getId()));
        verify(storage,never()).delete(any());
        service.editTourImages("2",List.of("shared.jpg"),List.of());em.flush();
        assertEquals(2,service.detachedImages(0).getTotalElements());
        references.save(FileReference.builder().fileId(detached.getId()).ownerType("LOCATION")
                .ownerId("999").createdAt(java.time.LocalDateTime.now()).build());
        assertThrows(IllegalArgumentException.class,()->service.permanentlyDeleteDetachedImage(detached.getId()));
        references.deleteByFileIdAndOwnerTypeAndOwnerId(detached.getId(), "LOCATION", "999");
        references.save(FileReference.builder().fileId(detached.getId()).ownerType("LOCATION")
                .ownerId("1000").createdAt(java.time.LocalDateTime.now()).build()); em.flush();
        assertThrows(IllegalArgumentException.class,()->service.permanentlyDeleteDetachedImage(detached.getId()));
        references.deleteByFileIdAndOwnerTypeAndOwnerId(detached.getId(), "LOCATION", "1000"); em.flush();
        service.permanentlyDeleteDetachedImage(detached.getId());em.flush();
        assertEquals(0,records.count());verify(storage).delete(any());
    }
    private static byte[] validPng() throws java.io.IOException {
        var out=new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(1,1,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",out);
        return out.toByteArray();
    }
}
