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
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(FileController.class)
@Import({SecurityConfig.class, DownloadThrottle.class})
class MediaAccessTest {
    @Autowired MockMvc mvc;
    @MockBean CustomJwtDecoder decoder;
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
    @Test void tourDeletionCanDetachItsGalleryWithDeletePermission() throws Exception {
        mvc.perform(multipart("/media/tour/7/edit")
                .file(new MockMultipartFile("changes", "", "application/json", "[]".getBytes()))
                .with(jwt().authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("PERM:TOURS_DELETE"))))
                .andExpect(status().isOk());
        verify(files).editTourImages(eq("7"), eq(java.util.List.of()), isNull());
    }
    @Test void cleanupIsPrivateAndPermanentDeletionNeedsDeletePermission() throws Exception {
        mvc.perform(get("/media/tour-orphans")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/media/tour-orphans/a").with(jwt())).andExpect(status().isForbidden());
        mvc.perform(delete("/media/tour-orphans/a").with(jwt().authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("PERM:TOURS_UPDATE")))).andExpect(status().isForbidden());
        mvc.perform(delete("/media/tour-orphans/a").with(jwt().authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("PERM:TOURS_DELETE")))).andExpect(status().isNoContent());
        verify(files).permanentlyDeleteDetachedImage("a");
    }
}
