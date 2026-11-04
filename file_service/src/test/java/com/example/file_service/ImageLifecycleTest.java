package com.example.file_service;

import com.cloudinary.Cloudinary;
import com.cloudinary.Uploader;
import com.cloudinary.utils.ObjectUtils;
import com.example.file_service.entity.FileMgmt;
import com.example.file_service.mapper.FileMgmtMapper;
import com.example.file_service.repository.*;
import com.example.file_service.service.*;
import com.example.file_service.dto.FileInfo;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.IOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImageLifecycleTest {
    final FileRepository storage=mock(FileRepository.class);
    final FileMgmtRepository records=mock(FileMgmtRepository.class);
    final FileMgmtMapper mapper=mock(FileMgmtMapper.class);
    final FileService service=new FileService(storage,records,mapper,mock(org.springframework.jdbc.core.JdbcTemplate.class),new TestTransactionTemplate(org.springframework.transaction.support.TransactionSynchronization.STATUS_COMMITTED));
    MockMultipartFile png(byte last) {
        try {
            var image = new java.awt.image.BufferedImage(1,1,java.awt.image.BufferedImage.TYPE_INT_RGB);
            image.setRGB(0,0,last);var out = new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",out);
            return new MockMultipartFile("files","x.png","image/png",out.toByteArray());
        } catch(java.io.IOException exception) { throw new AssertionError(exception); }
    }
    FileMgmt orphan() { return FileMgmt.builder().id("asset").tourId("null").ownerId("").path("folder/asset").url("https://res.cloudinary.com/demo/image/upload/v1/folder/asset.png").build(); }

    @Test void validatesAllFilesBeforeUploadingAnything() {
        var html=new MockMultipartFile("files","x.png","image/png","<html>bad</html>".getBytes());
        assertThrows(IllegalArgumentException.class,()->service.editTourImages("1",List.of(),List.of(png((byte)1),html)));
        assertThrows(IllegalArgumentException.class,()->ImageUploadPolicy.validate(Collections.nCopies(11,png((byte)1))));
        var large=new MockMultipartFile("files","large.png","image/png",new byte[5*1024*1024+1]);
        assertThrows(IllegalArgumentException.class,()->ImageUploadPolicy.validate(List.of(large)));
        verifyNoInteractions(storage,records);
    }
    @Test void replacingAnImageAtCapacityIsAllowedButAddingAnEleventhIsRejected() throws Exception {
        var current=new ArrayList<FileMgmt>(); for(int i=0;i<10;i++) current.add(FileMgmt.builder().tourId("1").url("old"+i).build());
        when(records.findByTourId("1")).thenReturn(current);
        assertThrows(IllegalArgumentException.class,()->service.editTourImages("1",List.of(),List.of(png((byte)1))));
        verifyNoInteractions(storage);
        when(records.findByTourIdAndUrlIn("1",List.of("old0"))).thenReturn(List.of(current.getFirst()));
        when(storage.store(any())).thenReturn(FileInfo.builder().url("new").path("new").build());
        when(mapper.toFileMgmt(any())).thenReturn(new FileMgmt());
        service.editTourImages("1",List.of("old0"),List.of(png((byte)1)));
        verify(storage).store(any());assertEquals("null",current.getFirst().getTourId());
    }
    @Test void identicalFilesOnRetryDoNotUploadOrAddAgain() throws Exception {
        var file=png((byte)1);
        String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(file.getBytes()));
        when(records.findByTourId("1")).thenReturn(List.of(FileMgmt.builder().tourId("1").url("existing").md5Checksum(hash).build()));
        service.uploadImageTour("1",List.of(file,file));
        verifyNoInteractions(storage);
    }
    @Test void secondUploadFailureCompensatesOnlyNewAssetsAndPreservesOldLinks() throws Exception {
        var old=FileMgmt.builder().tourId("1").url("old").build();
        when(records.findByTourIdAndUrlIn("1",List.of("old"))).thenReturn(List.of(old));
        when(records.findByTourId("1")).thenReturn(List.of(old));
        when(storage.store(any())).thenReturn(FileInfo.builder().url("new").path("new-public-id").build()).thenThrow(new IOException("fixture"));
        var fresh=FileMgmt.builder().path("new-public-id").url("new").build();when(mapper.toFileMgmt(any())).thenReturn(fresh);
        assertThrows(IOException.class,()->service.editTourImages("1",List.of("old"),List.of(png((byte)1),png((byte)2))));
        verify(storage).delete(fresh);verify(records,never()).saveAll(any());assertEquals("1",old.getTourId());
    }
    @Test void permanentDeletionRequiresDetachedVerifiedUnsharedAssetAndSuccessfulStorage() {
        var image=orphan();when(records.lockById("asset")).thenReturn(Optional.of(image));when(storage.canDelete(image)).thenReturn(true);
        image.setTourId("1");assertThrows(IllegalArgumentException.class,()->service.permanentlyDeleteDetachedImage("asset"));
        image.setTourId("null");when(storage.canDelete(image)).thenReturn(false);
        assertThrows(IllegalArgumentException.class,()->service.permanentlyDeleteDetachedImage("asset"));
        when(storage.canDelete(image)).thenReturn(true);
        when(records.lockAssetReferences(image.getPath(),image.getUrl())).thenReturn(List.of(FileMgmt.builder().tourId("2").url(image.getUrl()).build()));
        assertThrows(IllegalArgumentException.class,()->service.permanentlyDeleteDetachedImage("asset"));verify(storage,never()).delete(any());
        when(records.lockAssetReferences(image.getPath(),image.getUrl())).thenReturn(List.of(image));
        doThrow(new IllegalStateException("Cloudinary unavailable")).when(storage).delete(image);
        // The database delete is committed first; a Cloudinary failure is logged, not rolled back.
        assertDoesNotThrow(()->service.permanentlyDeleteDetachedImage("asset"));
        verify(records).deleteAll(List.of(image));verify(storage).delete(image);
    }
    @Test void destroyUsesStoredPublicIdAndValidatesCloudinaryResult() throws Exception {
        var cloud=spy(new Cloudinary(ObjectUtils.asMap("cloud_name","demo","api_key","test","api_secret","test")));
        var uploader=mock(Uploader.class);doReturn(uploader).when(cloud).uploader();
        var repository=new FileRepository();ReflectionTestUtils.setField(repository,"cloudinary",cloud);
        var image=orphan();when(uploader.destroy(eq("folder/asset"),anyMap())).thenReturn(Map.of("result","ok"));
        assertTrue(repository.canDelete(image));repository.delete(image);
        verify(uploader).destroy(eq("folder/asset"),argThat(options->Boolean.TRUE.equals(options.get("invalidate"))));
        image.setPath("v1/folder/asset");assertFalse(repository.canDelete(image));assertThrows(IllegalArgumentException.class,()->repository.delete(image));
        image.setPath("folder/asset");image.setUrl("https://res.cloudinary.com/another/image/upload/v1/folder/asset.png");assertFalse(repository.canDelete(image));
        image.setUrl(orphan().getUrl());when(uploader.destroy(anyString(),anyMap())).thenReturn(Map.of("result","error"));
        assertThrows(com.example.file_service.exception.MediaStorageException.class,()->repository.delete(image));
    }
    @Test void locationUploadsGetUniqueGeneratedIds() throws Exception {
        when(storage.store(any())).thenReturn(FileInfo.builder().name("same.png").url("url").path("path").build());
        when(mapper.toFileMgmt(any())).thenAnswer(i->FileMgmt.builder().id(((FileInfo)i.getArgument(0)).getName()).build());
        service.uploadFile(png((byte)1),7);
        service.uploadFile(png((byte)2),7);
        var saved=org.mockito.ArgumentCaptor.forClass(FileMgmt.class);
        verify(records,times(2)).saveAndFlush(saved.capture());
        var ids=saved.getAllValues().stream().map(FileMgmt::getId).toList();
        assertNotEquals("same.png",ids.get(0));
        assertNotEquals(ids.get(0),ids.get(1));
    }
    @Test void locationReplacementDetachesThePreviousImageForCleanup() throws Exception {
        var old = FileMgmt.builder().locationId(7).tourId("null").ownerId("").url("old").build();
        when(records.findByLocationId(7)).thenReturn(List.of(old));
        when(storage.store(any())).thenReturn(FileInfo.builder().url("new").path("new").build());
        when(mapper.toFileMgmt(any())).thenReturn(new FileMgmt());
        service.uploadFile(png((byte)1), 7);
        assertNull(old.getLocationId());
        verify(records).saveAll(List.of(old));
    }
    @Test void contentUploadsStopWhenTheCleanupBacklogIsFull() {
        when(records.countByTourIdAndOwnerIdAndLocationIdIsNullAndAvatarOwnerIdIsNull("null","")).thenReturn(500L);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.uploadFile(png((byte)1),7));
        verifyNoInteractions(storage);
    }
    @Test void locationDetachesAreWrittenBeforeTheReplacementIsInserted() throws Exception {
        var old=FileMgmt.builder().locationId(7).tourId("null").ownerId("").url("old").build();
        when(records.findByLocationId(7)).thenReturn(List.of(old));
        when(storage.store(any())).thenReturn(FileInfo.builder().url("new").path("new").build());
        when(mapper.toFileMgmt(any())).thenReturn(new FileMgmt());
        service.uploadFile(png((byte)1),7);
        // Hibernate orders inserts ahead of updates, so the released location key must flush first.
        var order=inOrder(records);
        order.verify(records).saveAll(List.of(old));
        order.verify(records).flush();
        order.verify(records).saveAndFlush(any());
    }
    @Test void aConcurrentLocationEditIsAConflictAndCleansTheFreshUpload() throws Exception {
        var rolledBack=new FileService(storage,records,mapper,mock(org.springframework.jdbc.core.JdbcTemplate.class),
            new TestTransactionTemplate(org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK));
        when(storage.store(any())).thenReturn(FileInfo.builder().url("new").path("new").build());
        when(mapper.toFileMgmt(any())).thenReturn(new FileMgmt());
        doThrow(new org.springframework.dao.DataIntegrityViolationException("ux_file_location")).when(records).saveAndFlush(any());
        var rejected=assertThrows(org.springframework.web.server.ResponseStatusException.class,()->rolledBack.uploadFile(png((byte)1),7));
        assertEquals(org.springframework.http.HttpStatus.CONFLICT,rejected.getStatusCode());
        verify(storage).delete(any());
    }
    @Test void anUnknownLocationIdIsRejectedBeforeAnyUpload() {
        assertThrows(IllegalArgumentException.class,()->service.uploadFile(png((byte)1),0));
        verifyNoInteractions(storage);
    }
}
