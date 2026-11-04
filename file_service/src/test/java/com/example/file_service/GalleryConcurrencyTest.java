package com.example.file_service;

import com.example.file_service.dto.FileInfo;
import com.example.file_service.entity.FileMgmt;
import com.example.file_service.mapper.FileMgmtMapperImpl;
import com.example.file_service.repository.*;
import com.example.file_service.service.FileService;
import org.junit.jupiter.api.Test;
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

@DataJpaTest(properties={"spring.datasource.url=jdbc:h2:mem:gallery_race;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
    "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Import({FileService.class,FileMgmtMapperImpl.class})
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class GalleryConcurrencyTest {
    @Autowired FileService service;
    @Autowired FileMgmtRepository records;
    @MockBean FileRepository storage;
    @Test void twoConcurrentUploadsCannotExceedTenImages() throws Exception {
        for(int i=0;i<8;i++)records.save(FileMgmt.builder().tourId("1").ownerId("").url("old"+i).build());
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var secondStarted=new CountDownLatch(1);var calls=new AtomicInteger();
        when(storage.store(any())).thenAnswer(call->{int number=calls.incrementAndGet();if(number==1){entered.countDown();assertTrue(release.await(15,TimeUnit.SECONDS));}return FileInfo.builder().url("new"+number).path("public"+number).build();});
        var pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->attempt(List.of(png(1),png(2))));assertTrue(entered.await(15,TimeUnit.SECONDS));
            var second=pool.submit(()->{secondStarted.countDown();return attempt(List.of(png(3),png(4)));});
            assertTrue(secondStarted.await(5,TimeUnit.SECONDS));release.countDown();
            // Both requests upload outside the lock; only one may link its images, the loser cleans up.
            assertTrue(first.get(20,TimeUnit.SECONDS)^second.get(20,TimeUnit.SECONDS));
            assertEquals(10,records.findByTourId("1").size());assertEquals(4,calls.get());
            verify(storage,times(2)).delete(any());
        }finally{release.countDown();pool.shutdownNow();}
    }
    private boolean attempt(List<org.springframework.web.multipart.MultipartFile> files) throws Exception {
        try{service.editTourImages("1",List.of(),files);return true;}
        catch(IllegalArgumentException rejected){return false;}
    }
    private MockMultipartFile png(int color) throws Exception {
        var image=new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB);image.setRGB(0,0,color);
        var out=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",out);
        return new MockMultipartFile("files","new.png","image/png",out.toByteArray());
    }
}
