package com.example.auth_service.service;

import com.example.auth_service.entity.UserStatus;
import com.example.auth_service.exception.*;
import com.example.auth_service.repository.UserRepository;
import jakarta.persistence.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** No HTTP runs under these locks. A late reply uses the latest committed avatar pointer. */
@Service @RequiredArgsConstructor
public class AvatarProfileWriter {
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    @PersistenceContext private EntityManager entityManager;
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public String apply(long id) {
        String owner=Long.toString(id);
        jdbc.update("INSERT INTO avatar_media_lock(owner_id) VALUES (?) ON DUPLICATE KEY UPDATE owner_id=owner_id",owner);
        jdbc.queryForObject("SELECT owner_id FROM avatar_media_lock WHERE owner_id=? FOR UPDATE",String.class,owner);
        var user=users.lockForLifecycle(id).orElseThrow(()->new AppException(ErrorCode.USER_NOT_EXISTED));
        entityManager.refresh(user,LockModeType.PESSIMISTIC_WRITE);
        if(user.getStatus()==UserStatus.ARCHIVED)throw new ResponseStatusException(HttpStatus.CONFLICT,"Không sửa tài khoản đã lưu trữ");
        var pointers=jdbc.queryForList("SELECT url FROM file_mgmt WHERE avatar_owner_id=?",String.class,owner);
        if(pointers.size()!=1 || pointers.getFirst()==null || pointers.getFirst().isBlank())
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Ảnh đại diện đã thay đổi; hãy tải lại");
        user.setAvatar(pointers.getFirst());users.save(user);
        return pointers.getFirst();
    }
}
