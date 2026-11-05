package com.example.file_service;

import com.example.file_service.dto.FileInfo;
import com.example.file_service.entity.FileMgmt;
import com.example.file_service.mapper.FileMgmtMapper;
import com.example.file_service.repository.*;
import com.example.file_service.service.FileService;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AvatarCleanupTest {
    FileRepository storage=mock(FileRepository.class);
    FileMgmtRepository records=mock(FileMgmtRepository.class);
    FileMgmtMapper mapper=mock(FileMgmtMapper.class);
    FileMgmt fresh=FileMgmt.builder().path("public-id").url("https://example.test/avatar").build();

    @BeforeEach void setup() throws Exception {
        when(storage.store(any())).thenReturn(FileInfo.builder().url(fresh.getUrl()).path(fresh.getPath()).build());
        when(mapper.toFileMgmt(any())).thenReturn(fresh);
    }

    private FileService service(int completionStatus) {
        return new FileService(storage,records,mapper,mock(JdbcTemplate.class),new TestTransactionTemplate(completionStatus));
    }

    @Test void uncertainCommitDoesNotDeleteAnAssetThatMayHaveBeenCommitted() throws Exception {
        service(TransactionSynchronization.STATUS_UNKNOWN).uploadAvt("2",png());
        verify(storage,never()).delete(any());
    }
    @Test void knownRollbackCompensatesOnlyTheFreshUpload() throws Exception {
        service(TransactionSynchronization.STATUS_ROLLED_BACK).uploadAvt("2",png());
        verify(storage).delete(fresh);
    }
    @Test void activeAvatarKeyCannotBeDeletedAsAnOrphanEvenIfLegacyOwnerMetadataIsWrong() {
        var active=FileMgmt.builder().ownerId("").tourId("null").avatarOwnerId("2").build();
        when(records.lockById("avatar")).thenReturn(java.util.Optional.of(active));
        assertThrows(IllegalArgumentException.class,()->service(TransactionSynchronization.STATUS_COMMITTED).permanentlyDeleteDetachedImage("avatar"));verify(storage,never()).delete(any());
    }
    MockMultipartFile png() throws Exception {
        var out=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(1,1,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",out);
        return new MockMultipartFile("file","avatar.png","image/png",out.toByteArray());
    }
}
