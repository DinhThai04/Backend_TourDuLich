package com.example.file_service;

import com.example.file_service.dto.FileInfo;
import com.example.file_service.entity.FileMgmt;
import com.example.file_service.mapper.FileMgmtMapperImpl;
import com.example.file_service.repository.*;
import com.example.file_service.service.FileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * A location keeps exactly one linked image; the unique key, not only the service, has to guarantee it.
 */
@DataJpaTest(properties={"spring.datasource.url=jdbc:h2:mem:location_image;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
    "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Import({FileService.class,FileMgmtMapperImpl.class})
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class LocationImageTest {
    @Autowired FileService service;
    @Autowired FileMgmtRepository records;
    @MockBean FileRepository storage;

    @BeforeEach void clean() { records.deleteAll(); reset(storage); }

    @Test void concurrentUploadsForOneLocationLeaveASingleLinkedImage() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var calls = new AtomicInteger();
        when(storage.store(any())).thenAnswer(call -> {
            // The upload must finish before the metadata transaction opens.
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            int number = calls.incrementAndGet();
            if (number == 1) { entered.countDown(); assertTrue(release.await(15, TimeUnit.SECONDS)); }
            return FileInfo.builder().url("location-" + number).path("public-" + number).build();
        });
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> attempt(7));
            assertTrue(entered.await(15, TimeUnit.SECONDS));
            var second = pool.submit(() -> { secondStarted.countDown(); return attempt(7); });
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
            release.countDown();
            var outcomes = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            // Two interleavings are legal: the second request either detaches the first (both succeed) or
            // loses the unique key and reports a readable conflict. Never two linked images, never a crash.
            for (var outcome : outcomes)
                assertTrue("ok".equals(outcome) || "conflict".equals(outcome), outcomes.toString());
            assertEquals(1, records.findAll().stream().filter(image -> Objects.equals(image.getLocationId(), 7)).count());
            // The loser either keeps its metadata for the cleanup page or has its asset deleted.
            assertEquals(outcomes.contains("conflict") ? 1 : 2, records.count());
            verify(storage, atMost(1)).delete(any());
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void replacementDetachesThePreviousImageAndKeepsItsMetadataForCleanup() throws Exception {
        records.save(FileMgmt.builder().locationId(7).tourId("null").ownerId("").url("old").build());
        when(storage.store(any())).thenReturn(FileInfo.builder().url("new").path("new-public-id").build());
        service.uploadFile(png(1), 7);
        var linked = records.findByLocationId(7);
        assertEquals(List.of("new"), linked.stream().map(FileMgmt::getUrl).toList());
        // The previous row must be visible to the cleanup page, which only lists detached assets.
        assertEquals(1, records.countByTourIdAndOwnerIdAndLocationIdIsNullAndAvatarOwnerIdIsNull("null", ""));
        verify(storage, never()).delete(any());
    }

    @Test void twoLinkedRowsForTheSameLocationAreRejectedByTheDatabase() {
        records.saveAndFlush(FileMgmt.builder().locationId(8).tourId("null").ownerId("").url("first").build());
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
            () -> records.saveAndFlush(FileMgmt.builder().locationId(8).tourId("null").ownerId("").url("second").build()));
        // Locations without an image keep NULL, which the unique key may repeat.
        records.saveAll(List.of(FileMgmt.builder().tourId("1").ownerId("").url("tour-a").build(),
                                 FileMgmt.builder().tourId("2").ownerId("").url("tour-b").build()));
        assertEquals(3, records.count());
    }

    /** Returns "ok", "conflict", or the rejection text so the race test can tell a conflict from a crash. */
    private String attempt(int locationId) {
        try { service.uploadFile(png(locationId), locationId); return "ok"; }
        catch (ResponseStatusException rejected) {
            return rejected.getStatusCode() == HttpStatus.CONFLICT ? "conflict" : rejected.getReason();
        }
        catch (Exception failure) { return failure.toString(); }
    }

    private MockMultipartFile png(int color) throws Exception {
        var image = new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, color);
        var out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", out);
        return new MockMultipartFile("file", "location.png", "image/png", out.toByteArray());
    }
}
