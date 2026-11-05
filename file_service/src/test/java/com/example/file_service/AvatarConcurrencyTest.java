package com.example.file_service;

import com.example.file_service.dto.FileInfo;
import com.example.file_service.entity.FileMgmt;
import com.example.file_service.mapper.FileMgmtMapperImpl;
import com.example.file_service.repository.*;
import com.example.file_service.service.FileService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest(properties={"spring.datasource.url=jdbc:h2:mem:avatar_race;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
    "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
    "spring.datasource.hikari.transaction-isolation=TRANSACTION_REPEATABLE_READ",
    "spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Import({FileService.class,FileMgmtMapperImpl.class})
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class AvatarConcurrencyTest {
    @Autowired FileService service;
    @Autowired FileMgmtRepository records;
    @MockBean FileRepository storage;
    @BeforeEach void clean(){records.deleteAll();reset(storage);}
    FileMgmt record(String owner,String avatar,String url){return FileMgmt.builder().ownerId(owner).avatarOwnerId(avatar).tourId("null").url(url).build();}
    @Test void genericFilesAndAvatarsUseSeparateKeys() throws Exception {
        records.save(record("2",null,"generic-1"));records.save(record("2",null,"generic-2"));
        when(storage.store(any())).thenReturn(FileInfo.builder().url("avatar-1").path("avatar-1").build());
        service.uploadAvt("2",png());
        assertEquals("avatar-1",service.getFileByOwnerId("2").url());
        assertEquals(3,records.count());assertEquals(1,records.findAll().stream().filter(f->f.getAvatarOwnerId()!=null).count());
    }
    @Test void concurrentReplacementsKeepOneActiveAvatarAndRetainOldMetadata() throws Exception {
        records.save(record("2",null,"generic"));records.save(record("2","2","old-avatar"));
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var started=new CountDownLatch(1);var calls=new AtomicInteger();
        when(storage.store(any())).thenAnswer(call->{
            // Cloudinary work must never run inside the metadata transaction.
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            int number=calls.incrementAndGet();if(number==1){entered.countDown();assertTrue(release.await(15,TimeUnit.SECONDS));}
            return FileInfo.builder().url("new-avatar-"+number).path("public-"+number).build();
        });
        var pool=Executors.newFixedThreadPool(2);var fixture=png();
        try {
            var first=pool.submit(()->service.uploadAvt("2",fixture));assertTrue(entered.await(15,TimeUnit.SECONDS));
            var second=pool.submit(()->{started.countDown();return service.uploadAvt("2",fixture);});
            assertTrue(started.await(5,TimeUnit.SECONDS));release.countDown();first.get(20,TimeUnit.SECONDS);second.get(20,TimeUnit.SECONDS);
            // Uploads happen before the lock, so either request may win; exactly one avatar stays active.
            assertEquals(4,records.count());
            assertTrue(List.of("new-avatar-1","new-avatar-2").contains(service.getFileByOwnerId("2").url()));
            assertEquals(1,records.findAll().stream().filter(f->f.getAvatarOwnerId()!=null).count());
            assertEquals(2,records.findAll().stream().filter(f->"".equals(f.getOwnerId())).count());
            verify(storage,never()).delete(any());
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test void failedCloudUploadPreservesTheCurrentAvatar() throws Exception {
        records.save(record("2","2","old-avatar"));when(storage.store(any())).thenThrow(new java.io.IOException("fixture"));
        assertThrows(java.io.IOException.class,()->service.uploadAvt("2",png()));
        assertEquals("old-avatar",service.getFileByOwnerId("2").url());assertEquals(1,records.count());
    }
    @Test void databaseRejectsTwoActiveAvatarKeysButAllowsMultipleGenericFiles() {
        records.saveAndFlush(record("2","2","avatar"));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->records.saveAndFlush(record("2","2","other")));
        records.saveAndFlush(record("2",null,"generic"));assertEquals(2,records.count());
    }
    private MockMultipartFile png() throws Exception {
        var out=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",out);
        return new MockMultipartFile("file","avatar.png","image/png",out.toByteArray());
    }
}
