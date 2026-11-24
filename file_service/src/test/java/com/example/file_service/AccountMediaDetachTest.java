package com.example.file_service;

import com.example.file_service.mapper.FileMgmtMapper;
import com.example.file_service.repository.FileMgmtRepository;
import com.example.file_service.repository.FileReferenceRepository;
import com.example.file_service.repository.FileRepository;
import com.example.file_service.service.AvatarWritePolicy;
import com.example.file_service.service.FileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class AccountMediaDetachTest {
    private JdbcTemplate jdbc;
    private FileService service;

    @BeforeEach void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:account_media_" + java.util.UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE file_inbox(consumer VARCHAR(120),event_id VARCHAR(36),event_type VARCHAR(120),received_at TIMESTAMP,processed_at TIMESTAMP,PRIMARY KEY(consumer,event_id))");
        jdbc.execute("CREATE TABLE avatar_media_lock(owner_id VARCHAR(19) PRIMARY KEY,deleted_at TIMESTAMP NULL,avatar_version BIGINT NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE file_mgmt(id VARCHAR(36) PRIMARY KEY,owner_id VARCHAR(255),tour_id VARCHAR(255),avatar_owner_id VARCHAR(19),location_id INT)");
        jdbc.execute("CREATE TABLE file_reference(id BIGINT AUTO_INCREMENT PRIMARY KEY,file_id VARCHAR(36),owner_type VARCHAR(20),owner_id VARCHAR(80),created_at TIMESTAMP)");
        jdbc.update("INSERT INTO file_mgmt VALUES('avatar-file','41','null','41',NULL),('generic-file','41','null',NULL,NULL),('tour-file','41','9',NULL,NULL),('location-file','41','null',NULL,3)");
        jdbc.update("INSERT INTO file_reference(file_id,owner_type,owner_id,created_at) VALUES('avatar-file','AVATAR','41',CURRENT_TIMESTAMP)");
        service = new FileService(mock(FileRepository.class), mock(FileMgmtRepository.class), mock(FileReferenceRepository.class),
                mock(FileMgmtMapper.class), jdbc,
                new TestTransactionTemplate(org.springframework.transaction.support.TransactionSynchronization.STATUS_COMMITTED),
                mock(AvatarWritePolicy.class));
    }

    @Test void accountDeletionDetachesOnlyAccountMediaAndReplaysSafely() {
        String eventId = java.util.UUID.randomUUID().toString();
        service.detachAccountMedia("41", eventId);
        service.detachAccountMedia("41", eventId);

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM file_inbox WHERE consumer='file.account.media.detach.v1'", Integer.class));
        assertNotNull(jdbc.queryForObject("SELECT deleted_at FROM avatar_media_lock WHERE owner_id='41'", java.time.LocalDateTime.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM file_mgmt WHERE avatar_owner_id IS NOT NULL", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM file_mgmt WHERE owner_id='' AND tour_id='null' AND location_id IS NULL", Integer.class));
        assertEquals("9", jdbc.queryForObject("SELECT tour_id FROM file_mgmt WHERE id='tour-file'", String.class));
        assertEquals(3, jdbc.queryForObject("SELECT location_id FROM file_mgmt WHERE id='location-file'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM file_reference WHERE owner_type='AVATAR' AND owner_id='41'", Integer.class));
    }

    @Test void invalidOwnerOrEventIsRejectedBeforeInboxWrite() {
        assertThrows(IllegalArgumentException.class, () -> service.detachAccountMedia("0", java.util.UUID.randomUUID().toString()));
        assertThrows(IllegalArgumentException.class, () -> service.detachAccountMedia("41", "not-a-uuid"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM file_inbox", Integer.class));
    }
}
