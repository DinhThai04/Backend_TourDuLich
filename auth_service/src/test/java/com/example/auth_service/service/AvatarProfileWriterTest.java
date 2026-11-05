package com.example.auth_service.service;

import com.example.auth_service.entity.*;
import com.example.auth_service.repository.*;
import com.example.auth_service.mapper.UserMapper;
import com.example.auth_service.repository.httpclient.FileServiceClient;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest(properties={"spring.datasource.url=jdbc:h2:mem:avatar_profile;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
    "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
    "spring.datasource.hikari.transaction-isolation=TRANSACTION_REPEATABLE_READ",
    "spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Import(AvatarProfileWriter.class)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class AvatarProfileWriterTest {
    @Autowired AvatarProfileWriter writer;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    User user;
    @BeforeEach void setup(){
        jdbc.execute("CREATE TABLE IF NOT EXISTS avatar_media_lock(owner_id VARCHAR(19) PRIMARY KEY)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS file_mgmt(id VARCHAR(36) PRIMARY KEY,avatar_owner_id VARCHAR(19) UNIQUE,url VARCHAR(2048))");
        jdbc.update("DELETE FROM file_mgmt");users.deleteAll();roles.deleteAll();
        var role=new Role();role.setName("customer");role.setActive(true);role=roles.save(role);
        user=users.save(User.builder().username("avatar-customer").email("avatar@test.invalid").password("hash").role(role).avatar("old-url").build());
    }
    void pointer(String url){jdbc.update("INSERT INTO file_mgmt VALUES('active',?,?)",user.getId().toString(),url);}
    @Test void lateUploadResponseUsesLatestCommittedPointer() throws Exception {
        pointer("latest-url");var files=mock(FileServiceClient.class);
        when(files.uploadAvt(anyString(),any())).thenReturn(com.example.auth_service.dto.response.FileResponse.builder().url("stale-upload-url").build());
        var service=new UserService(mock(AccountLifecycleService.class),users,mock(UserMapper.class),mock(org.springframework.security.crypto.password.PasswordEncoder.class),files,roles,mock(com.example.auth_service.config.AccountAccess.class),mock(AdminContinuity.class),writer,mock(SessionEpochService.class));
        // uploadAvatar now scopes staff writes; authenticate as the owner so this stays a self-service upload.
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(user.getId().toString(),"",List.of()));
        try {
            var response=service.uploadAvatar(user.getId().toString(),new org.springframework.mock.web.MockMultipartFile("file","avatar.png","image/png",new byte[]{1}));
            assertEquals("latest-url",response.getUrl());assertEquals("latest-url",users.findById(user.getId()).orElseThrow().getAvatar());
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }
    @Test void concurrentProfileWritesCannotRestoreAStaleAvatar() throws Exception {
        pointer("latest-url");var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            List<Future<String>> replies=new ArrayList<>();for(int i=0;i<2;i++)replies.add(pool.submit(()->{start.await();return writer.apply(user.getId());}));
            start.countDown();for(var reply:replies)assertEquals("latest-url",reply.get(20,TimeUnit.SECONDS));
            assertEquals("latest-url",users.findById(user.getId()).orElseThrow().getAvatar());
        }finally{pool.shutdownNow();}
    }
    @Test void missingPointerDoesNotEraseTheDisplayedAvatar() {
        assertThrows(ResponseStatusException.class,()->writer.apply(user.getId()));assertEquals("old-url",users.findById(user.getId()).orElseThrow().getAvatar());
    }
    @Test void archivedAccountCannotBeModifiedByALateUpload() {
        pointer("new-url");user.setStatus(UserStatus.ARCHIVED);users.save(user);
        assertThrows(ResponseStatusException.class,()->writer.apply(user.getId()));assertEquals("old-url",users.findById(user.getId()).orElseThrow().getAvatar());
    }
}
