package com.example.auth_service.service;

import com.example.auth_service.entity.Role;
import com.example.auth_service.entity.User;
import com.example.auth_service.entity.UserStatus;
import com.example.auth_service.mapper.UserMapper;
import com.example.auth_service.repository.RoleRepository;
import com.example.auth_service.repository.UserRepository;
import com.example.auth_service.repository.httpclient.FileServiceClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:avatar_profile;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(AvatarProfileWriter.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AvatarProfileWriterTest {
    @Autowired AvatarProfileWriter writer;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    User user;

    @BeforeEach void setup() {
        users.deleteAll();
        roles.deleteAll();
        var role = new Role();
        role.setName("customer");
        role.setActive(true);
        role = roles.save(role);
        user = users.saveAndFlush(User.builder().username("avatar-customer").email("avatar@test.invalid")
                .password("hash").role(role).avatar("old-url").build());
    }

    @Test void lateUploadResponseKeepsTheNewerRevisionAlreadyStoredInAuth() throws Exception {
        user.setAvatar("latest-url");
        user.setAvatarVersion(2);
        users.saveAndFlush(user);
        var files = mock(FileServiceClient.class);
        when(files.uploadAvt(anyString(), any())).thenReturn(
                com.example.auth_service.dto.response.FileResponse.builder().url("stale-upload-url").avatarVersion(1L).build());
        var service = new UserService(mock(AccountLifecycleService.class), users, mock(UserMapper.class),
                mock(org.springframework.security.crypto.password.PasswordEncoder.class), files, roles,
                mock(com.example.auth_service.config.AccountAccess.class), mock(AdminContinuity.class), writer,
                mock(SessionEpochService.class));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(user.getId().toString(), "", List.of()));
        try {
            var response = service.uploadAvatar(user.getId().toString(),
                    new org.springframework.mock.web.MockMultipartFile("file", "avatar.png", "image/png", new byte[]{1}));
            assertEquals("latest-url", response.getUrl());
            assertEquals("latest-url", users.findById(user.getId()).orElseThrow().getAvatar());
            assertEquals(2, users.findById(user.getId()).orElseThrow().getAvatarVersion());
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test void concurrentProfileUpdatesAlwaysConvergeOnTheHighestRevision() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            List<Future<String>> replies = new ArrayList<>();
            replies.add(pool.submit(() -> { start.await(); return writer.apply(user.getId(), "revision-one", 1L); }));
            replies.add(pool.submit(() -> { start.await(); return writer.apply(user.getId(), "revision-two", 2L); }));
            start.countDown();
            for (var reply : replies) reply.get(20, java.util.concurrent.TimeUnit.SECONDS);
            var stored = users.findById(user.getId()).orElseThrow();
            assertEquals(2, stored.getAvatarVersion());
            assertEquals("revision-two", stored.getAvatar());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test void sameRevisionWithDifferentUrlIsRejected() {
        writer.apply(user.getId(), "revision-one", 1L);
        var conflict = assertThrows(ResponseStatusException.class,
                () -> writer.apply(user.getId(), "different-url", 1L));
        assertEquals(409, conflict.getStatusCode().value());
        assertEquals("revision-one", users.findById(user.getId()).orElseThrow().getAvatar());
    }

    @Test void fileRevisionIsAcceptedFromFileResponseButNotReturnedToTheBrowser() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var upload = mapper.readValue("{\"url\":\"avatar-url\",\"avatarVersion\":7}",
                com.example.auth_service.dto.response.FileResponse.class);

        assertEquals(7L, upload.getAvatarVersion());
        assertFalse(mapper.writeValueAsString(upload).contains("avatarVersion"));
    }

    @Test void invalidRevisionAndArchivedAccountCannotChangeTheAvatar() {
        assertThrows(ResponseStatusException.class, () -> writer.apply(user.getId(), "new-url", null));
        user.setStatus(UserStatus.ARCHIVED);
        users.saveAndFlush(user);
        assertThrows(ResponseStatusException.class, () -> writer.apply(user.getId(), "new-url", 1L));
        assertEquals("old-url", users.findById(user.getId()).orElseThrow().getAvatar());
    }
}
