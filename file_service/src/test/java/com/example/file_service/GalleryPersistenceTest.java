package com.example.file_service;

import com.example.file_service.dto.FileInfo;
import com.example.file_service.entity.FileMgmt;
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
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase(replace=org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE)
@DataJpaTest(properties={"spring.datasource.url=jdbc:h2:mem:gallery_persistence;MODE=MySQL;DB_CLOSE_DELAY=-1","spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=","spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"})
@Import({FileService.class, FileMgmtMapperImpl.class})
// Transaction-bound upload cleanup fires on the test rollback, after Mockito has reset the shared mock.
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class GalleryPersistenceTest {
    @Autowired FileService service;
    @Autowired FileMgmtRepository records;
    @Autowired EntityManager em;
    @MockBean FileRepository storage;
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
        verify(storage,never()).delete(any());
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
        em.createNativeQuery("INSERT INTO location(id,img) VALUES(999,'shared.jpg')").executeUpdate();
        assertThrows(IllegalArgumentException.class,()->service.permanentlyDeleteDetachedImage(detached.getId()));
        em.createNativeQuery("DELETE FROM location WHERE id=999").executeUpdate();
        service.permanentlyDeleteDetachedImage(detached.getId());em.flush();
        assertEquals(0,records.count());verify(storage).delete(any());
    }
    private static byte[] validPng() throws java.io.IOException {
        var out=new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(1,1,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",out);
        return out.toByteArray();
    }
}
