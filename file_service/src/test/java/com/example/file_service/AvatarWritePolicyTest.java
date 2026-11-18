package com.example.file_service;

import com.example.file_service.dto.FileInfo;
import com.example.file_service.entity.FileMgmt;
import com.example.file_service.mapper.FileMgmtMapper;
import com.example.file_service.repository.*;
import com.example.file_service.service.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AvatarWritePolicyTest {
    JdbcTemplate jdbc;
    AvatarWritePolicy policy;
    FileRepository storage = mock(FileRepository.class);
    FileMgmtRepository records = mock(FileMgmtRepository.class);
    FileMgmtMapper mapper = mock(FileMgmtMapper.class);

    @BeforeEach void fixture() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:avatar_policy_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE roles(id INT PRIMARY KEY,name VARCHAR(50),active BOOLEAN)");
        jdbc.execute("CREATE TABLE avatar_media_lock(owner_id VARCHAR(100) PRIMARY KEY)");
        jdbc.execute("CREATE TABLE users(id INT PRIMARY KEY,status VARCHAR(20),role_id INT)");
        jdbc.update("INSERT INTO roles VALUES(1,'admin',TRUE),(2,'Khách Hàng',TRUE),(3,'Staff',TRUE)");
        jdbc.update("INSERT INTO users VALUES(1,'ACTIVE',1),(2,'ACTIVE',2),(3,'ACTIVE',3),(4,'ARCHIVED',2)");
        policy = new AvatarWritePolicy(jdbc, "Khách Hàng");
    }

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    void login(int id) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                Integer.toString(id), "", List.of(new SimpleGrantedAuthority("PERM:USERS_UPDATE"))));
    }

    FileService service() {
        return new FileService(storage,records,mapper,jdbc,
                new TestTransactionTemplate(TransactionSynchronization.STATUS_COMMITTED),policy);
    }

    MockMultipartFile png() throws Exception {
        var output=new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(1,1,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",output);
        return new MockMultipartFile("file","avatar.png","image/png",output.toByteArray());
    }

    @Test void directMediaWritesCannotBypassStaffTargetPolicy() throws Exception {
        login(3);
        var file=png();
        assertEquals(403,assertThrows(ResponseStatusException.class,()->service().uploadAvt("1",file)).getStatusCode().value());
        assertEquals(409,assertThrows(ResponseStatusException.class,()->service().uploadAvt("4",file)).getStatusCode().value());
        assertEquals(404,assertThrows(ResponseStatusException.class,()->service().uploadAvt("999",file)).getStatusCode().value());
        verifyNoInteractions(storage,records,mapper);
        assertDoesNotThrow(()->policy.requireWritable("2",false));
        assertDoesNotThrow(()->policy.requireWritable("3",false));
    }

    @Test void adminCanEditStaffButCannotEditArchivedTarget() {
        login(1);
        assertDoesNotThrow(()->policy.requireWritable("3",false));
        assertEquals(409,assertThrows(ResponseStatusException.class,()->policy.requireWritable("4",false)).getStatusCode().value());
    }

    @Test void targetArchivedDuringUploadIsRecheckedAndFreshAssetCompensated() throws Exception {
        login(3);
        var fresh=FileMgmt.builder().path("fresh").url("https://example.test/fresh.png").build();
        when(storage.store(any())).thenAnswer(call->{
            jdbc.update("UPDATE users SET status='ARCHIVED' WHERE id=2");
            return FileInfo.builder().path("fresh").url(fresh.getUrl()).build();
        });
        when(mapper.toFileMgmt(any())).thenReturn(fresh);
        // The unit transaction helper reports a known rollback so cleanup can be verified.
        var service=new FileService(storage,records,mapper,jdbc,
                new TestTransactionTemplate(TransactionSynchronization.STATUS_ROLLED_BACK),policy);
        assertEquals(409,assertThrows(ResponseStatusException.class,()->service.uploadAvt("2",png())).getStatusCode().value());
        verify(storage).delete(fresh);
        verify(records,never()).saveAndFlush(any());
    }
}
