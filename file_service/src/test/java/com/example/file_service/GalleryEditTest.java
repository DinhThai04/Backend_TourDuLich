package com.example.file_service;

import com.example.file_service.repository.*;
import com.example.file_service.mapper.FileMgmtMapper;
import com.example.file_service.service.FileService;
import com.example.file_service.dto.FileInfo;
import com.example.file_service.entity.FileMgmt;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import java.io.IOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GalleryEditTest {
    private final FileRepository storage=mock(FileRepository.class);
    private final FileMgmtRepository records=mock(FileMgmtRepository.class);
    private final FileMgmtMapper mapper=mock(FileMgmtMapper.class);
    private final FileService service=new FileService(storage,records,mapper,mock(org.springframework.jdbc.core.JdbcTemplate.class),new TestTransactionTemplate(org.springframework.transaction.support.TransactionSynchronization.STATUS_COMMITTED));
    @Test void removalsAreTourScopedAndDoNotDeleteCloudinaryAssets() throws Exception {
        var old=new FileMgmt();old.setTourId("1");old.setUrl("shared.jpg");
        when(records.findByTourIdAndUrlIn("1",List.of("shared.jpg"))).thenReturn(List.of(old));
        service.editTourImages("1",List.of("shared.jpg"),List.of());
        assertEquals("null",old.getTourId()); verify(records).saveAll(List.of(old)); verify(storage,never()).delete(any());
    }
    @Test void failedUploadCannotRemoveOldGalleryLinks() throws Exception {
        var old=new FileMgmt();old.setUrl("old.jpg");
        when(records.findByTourIdAndUrlIn("1",List.of("old.jpg"))).thenReturn(List.of(old));
        var file=new MockMultipartFile("files","new.png","image/png",validPng());
        when(storage.store(file)).thenThrow(new IOException("fixture"));
        assertThrows(IOException.class,()->service.editTourImages("1",List.of("old.jpg"),List.of(file)));
        verify(records,never()).deleteAll(any()); verify(records,never()).saveAll(any());
    }
    @Test void anotherToursImageCannotBeDetached() {
        when(records.findByTourIdAndUrlIn("1",List.of("other-tour.jpg"))).thenReturn(List.of());
        assertThrows(IllegalArgumentException.class,()->service.editTourImages("1",List.of("other-tour.jpg"),List.of()));
        verify(records,never()).deleteAll(any());
    }
    private static byte[] validPng() throws java.io.IOException {
        var out=new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(1,1,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",out);
        return out.toByteArray();
    }
}
