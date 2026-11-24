package com.example.auth_service.service;

import com.example.auth_service.entity.User;
import com.example.auth_service.entity.UserStatus;
import com.example.auth_service.exception.AppException;
import com.example.auth_service.exception.ErrorCode;
import com.example.auth_service.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Applies File-owned avatar revisions to Auth's profile without reading File's database. */
@Service
@RequiredArgsConstructor
public class AvatarProfileWriter {
    private final UserRepository users;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public String apply(long accountId, String url, Long revision) {
        if (url == null || url.isBlank() || url.length() > 255 || revision == null || revision < 1)
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Dịch vụ tệp trả về thông tin avatar không hợp lệ");

        User user = users.lockForLifecycle(accountId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_EXISTED));
        if (user.getStatus() == UserStatus.ARCHIVED)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Không sửa tài khoản đã lưu trữ");

        if (revision > user.getAvatarVersion()) {
            user.setAvatar(url);
            user.setAvatarVersion(revision);
            users.save(user);
        } else if (revision == user.getAvatarVersion() && !url.equals(user.getAvatar())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Phiên bản avatar không khớp; hãy tải lại");
        }

        return user.getAvatar();
    }
}
