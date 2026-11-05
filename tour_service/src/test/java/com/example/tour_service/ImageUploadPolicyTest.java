package com.example.tour_service;
import com.example.tour_service.service.ImageUploadPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class ImageUploadPolicyTest {
    MockMultipartFile png(int width,int height) throws Exception {
        var bytes=new ByteArrayOutputStream();javax.imageio.ImageIO.write(new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB),"png",bytes);
        return new MockMultipartFile("files","image.png","image/png",bytes.toByteArray());
    }
    @Test void permitsActualPngAndJpeg() throws Exception {
        assertDoesNotThrow(()->ImageUploadPolicy.validate(List.of(png(2,2))));
        var bytes=new ByteArrayOutputStream();javax.imageio.ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"jpeg",bytes);
        assertDoesNotThrow(()->ImageUploadPolicy.validate(List.of(new MockMultipartFile("files","a.jpg","image/jpeg",bytes.toByteArray()))));
    }
    @Test void fakeHeaderAndMimeMismatchCannotPass() throws Exception {
        assertThrows(IllegalArgumentException.class,()->ImageUploadPolicy.validate(List.of(new MockMultipartFile("files","fake.png","image/png",new byte[]{(byte)137,80,78,71,13,10,26,10}))));
        assertThrows(IllegalArgumentException.class,()->ImageUploadPolicy.validate(List.of(new MockMultipartFile("files","fake.jpg","image/jpeg",png(1,1).getBytes()))));
    }
    @Test void pixelBombIsRejectedFromHeaderBeforeImageAllocation() throws Exception {
        byte[] bytes=png(1,1).getBytes();var buffer=java.nio.ByteBuffer.wrap(bytes);buffer.putInt(16,5000);buffer.putInt(20,5000);
        var crc=new java.util.zip.CRC32();crc.update(bytes,12,17);buffer.putInt(29,(int)crc.getValue());
        var error=assertThrows(IllegalArgumentException.class,()->ImageUploadPolicy.validate(List.of(new MockMultipartFile("files","huge.png","image/png",bytes))));
        assertTrue(error.getMessage().contains("20"));
    }
    @Test void totalUploadAndAvatarLimitsAreEnforcedBeforeDecoding() {
        var full=new MockMultipartFile("files","a.png","image/png",new byte[5*1024*1024]);
        assertThrows(IllegalArgumentException.class,()->ImageUploadPolicy.validate(java.util.Collections.nCopies(6,full)));
        var avatar=new MockMultipartFile("file","a.png","image/png",new byte[2*1024*1024+1]);
        assertThrows(IllegalArgumentException.class,()->ImageUploadPolicy.validateAvatar(avatar));
    }
    @Test void animatedAndVectorUploadsAreRejected() {
        for(String mime:List.of("image/gif","image/svg+xml"))assertThrows(IllegalArgumentException.class,()->ImageUploadPolicy.validate(List.of(new MockMultipartFile("files","file",mime,"<svg/>".getBytes()))));
    }
    @Test void acceptsExistingProjectWebpWithInstalledDecoder() throws Exception {
        try(var input=getClass().getResourceAsStream("/fixtures/existing-tag1.webp")) {
            assertNotNull(input);
            byte[] bytes=input.readAllBytes();
            assertDoesNotThrow(()->ImageUploadPolicy.validate(List.of(new MockMultipartFile("files","tag.webp","image/webp",bytes))));
        }
    }
    @Test void decodeBudgetScalesWithPixelsAndCapsAtThePool() {
        assertEquals(1,ImageUploadPolicy.decodePermits(2,2));
        assertEquals(2,ImageUploadPolicy.decodePermits(1500,1000));
        assertEquals(8,ImageUploadPolicy.decodePermits(5000,4000));
    }
    @Test void largeImageStillPassesAndFreesEveryPermit() throws Exception {
        assertDoesNotThrow(()->ImageUploadPolicy.validate(List.of(png(3000,3000))));
        // A 3000x3000 image takes the whole pool, so a leaked permit would answer 429 here.
        assertDoesNotThrow(()->ImageUploadPolicy.validate(List.of(png(3000,3000))));
    }
}
