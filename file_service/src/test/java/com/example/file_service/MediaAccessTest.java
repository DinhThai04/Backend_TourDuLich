package com.example.file_service;

import com.example.file_service.config.*;
import com.example.file_service.controller.FileController;
import com.example.file_service.service.FileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = FileController.class, properties = "jwt.signerKey=fixture-only-gallery-signing-key")
@Import({SecurityConfig.class, DownloadThrottle.class, TourGalleryAuthorization.class})
class MediaAccessTest {
    @Autowired MockMvc mvc;
    @MockBean CustomJwtDecoder decoder;
    @MockBean FileInternalSecurityProperties internalFileSecurityProperties;
    @MockBean FileService files;
    @Test void customerCannotReplaceOtherAvatarOrTourImages() throws Exception {
        mvc.perform(multipart("/media/uploadAvt/3").file(new MockMultipartFile("file", "x.png", "image/png", new byte[]{1}))
                .with(jwt().jwt(j -> j.subject("2")))).andExpect(status().isForbidden());
        mvc.perform(multipart("/media/uploadTour/1").file(new MockMultipartFile("files", "x.png", "image/png", new byte[]{1}))
                .with(jwt().jwt(j -> j.subject("2")))).andExpect(status().isForbidden());
        verifyNoInteractions(files);
    }
    @Test void customerCanUploadOwnAvatar() throws Exception {
        mvc.perform(multipart("/media/uploadAvt/2").file(new MockMultipartFile("file", "x.png", "image/png", new byte[]{1}))
                .with(jwt().jwt(j -> j.subject("2")))).andExpect(status().isOk());
        verify(files).uploadAvt(eq("2"), any());
    }
    @Test void mediaReadsArePublicOnlyWithBoundedTourIds() throws Exception {
        mvc.perform(get("/media/tours").param("tourIds", "1")).andExpect(status().isOk());
        verify(files).getTourMedia(java.util.List.of("1"));
        mvc.perform(get("/media/tours")).andExpect(status().isBadRequest());
    }
    @Test void createOrDeletePermissionDoesNotAuthorizeGalleryEdits() throws Exception {
        mvc.perform(multipart("/media/tour/7/edit")
                .file(changes())
                .with(jwt().authorities(new SimpleGrantedAuthority("PERM:TOURS_DELETE"))))
                .andExpect(status().isForbidden());
        mvc.perform(multipart("/media/uploadTour/7").file(image())
                .with(jwt().authorities(new SimpleGrantedAuthority("PERM:TOURS_CREATE"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(files);
    }

    @Test void creationProofIsRequiredAndBoundToTheTourAndAccount() throws Exception {
        var creator = jwt().jwt(token -> token.subject("42"))
                .authorities(new SimpleGrantedAuthority("PERM:TOURS_CREATE"));
        mvc.perform(multipart("/media/tour/7/initial").file(image()).with(creator))
                .andExpect(status().isForbidden());
        mvc.perform(multipart("/media/tour/8/initial").file(image())
                .header("X-Tour-Gallery-Proof", proof("initial", 7, 42)).with(creator))
                .andExpect(status().isForbidden());
        mvc.perform(multipart("/media/tour/7/initial").file(image())
                .header("X-Tour-Gallery-Proof", proof("initial", 7, 43)).with(creator))
                .andExpect(status().isForbidden());
        verifyNoInteractions(files);
        mvc.perform(multipart("/media/tour/7/initial").file(image())
                .header("X-Tour-Gallery-Proof", proof("initial", 7, 42)).with(creator))
                .andExpect(status().isOk());
        verify(files).uploadImageTour(eq("7"), anyList());
    }

    @Test void editAndRetryRoutesRequireTourIssuedProof() throws Exception {
        var editor = jwt().jwt(token -> token.subject("42"))
                .authorities(new SimpleGrantedAuthority("PERM:TOURS_UPDATE"));
        mvc.perform(multipart("/media/tour/7/edit").file(changes()).with(editor))
                .andExpect(status().isForbidden());
        mvc.perform(multipart("/media/tour/8/edit").file(changes())
                .header("X-Tour-Gallery-Proof", proof("edit", 7, 42)).with(editor))
                .andExpect(status().isForbidden());
        mvc.perform(multipart("/media/tour/7/edit").file(changes())
                .header("X-Tour-Gallery-Proof", proof("edit", 7, 42)).with(editor))
                .andExpect(status().isOk());

        mvc.perform(multipart("/media/uploadTour/7").file(image()).with(editor))
                .andExpect(status().isForbidden());
        mvc.perform(multipart("/media/uploadTour/7").file(image())
                .header("X-Tour-Gallery-Proof", proof("retry", 7, 42)).with(editor))
                .andExpect(status().isOk());
        verify(files).editTourImages(eq("7"), anyList(), isNull());
        verify(files).uploadImageTour(eq("7"), anyList());
    }

    @Test void deletionOnlyAuthorityCannotUseTheInitialUploadEndpoint() throws Exception {
        var deleter = jwt().jwt(token -> token.subject("42"))
                .authorities(new SimpleGrantedAuthority("PERM:TOURS_DELETE"));
        mvc.perform(multipart("/media/tour/7/initial").file(image())
                .header("X-Tour-Gallery-Proof", proof("initial", 7, 42)).with(deleter))
                .andExpect(status().isForbidden());
        verifyNoInteractions(files);
    }
    @Test void cleanupIsPrivateAndPermanentDeletionNeedsDeletePermission() throws Exception {
        mvc.perform(get("/media/tour-orphans")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/media/tour-orphans/a").with(jwt())).andExpect(status().isForbidden());
        mvc.perform(delete("/media/tour-orphans/a").with(jwt().authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("PERM:TOURS_UPDATE")))).andExpect(status().isForbidden());
        mvc.perform(delete("/media/tour-orphans/a").with(jwt().authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("PERM:TOURS_DELETE")))).andExpect(status().isNoContent());
        verify(files).permanentlyDeleteDetachedImage("a");
    }

    private static MockMultipartFile changes() {
        return new MockMultipartFile("changes", "", "application/json", "[]".getBytes(StandardCharsets.UTF_8));
    }

    private static MockMultipartFile image() {
        return new MockMultipartFile("files", "x.png", "image/png", new byte[]{1});
    }

    private static String proof(String action, int tourId, int accountId) throws Exception {
        long expires = Instant.now().plusSeconds(90).getEpochSecond();
        String input = String.join("\n", "sgu-tour-gallery-v1", action, String.valueOf(tourId), String.valueOf(accountId), String.valueOf(expires));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("fixture-only-gallery-signing-key".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return expires + "." + HexFormat.of().formatHex(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
    }
}
