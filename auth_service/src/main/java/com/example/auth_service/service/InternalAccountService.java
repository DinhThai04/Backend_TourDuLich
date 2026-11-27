package com.example.auth_service.service;

import com.example.auth_service.dto.internal.InternalUserResponse;
import com.example.auth_service.entity.User;
import com.example.auth_service.entity.Role;
import com.example.auth_service.entity.UserStatus;
import com.example.auth_service.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class InternalAccountService {
    private final UserRepository userRepository;

    @Value("${app.registration.customer-role-name:Khách Hàng}")
    private String customerRoleName;

    public record AccountStatus(Long id, String status, boolean tourGuide) {}

    @Transactional(readOnly = true)
    public Optional<InternalUserResponse> findUser(Long id) {
        return userRepository.findById(id).map(InternalAccountService::toInternalResponse);
    }

    @Transactional(readOnly = true)
    public Optional<AccountStatus> findStatus(Long id) {
        return userRepository.findById(id).map(user -> {
            var role = user.getRole();
            boolean guide = role != null && Boolean.TRUE.equals(role.getActive())
                    && role.getPermissions().stream().anyMatch(permission -> "TOUR_GUIDE_OPERATE".equals(permission.getCode()));
            return new AccountStatus(user.getId(), user.getStatus().name(), guide);
        });
    }

    @Transactional(readOnly = true)
    public List<InternalUserResponse> findUsers(List<Long> ids) {
        return userRepository.findAllById(ids.stream().distinct().toList()).stream()
                .map(InternalAccountService::toInternalResponse)
                .toList();
    }

    private static InternalUserResponse toInternalResponse(User user) {
        return InternalUserResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .phone(user.getPhone())
                .avatar(user.getAvatar())
                .username(user.getUsername())
                .status(user.getStatus().name())
                .role(user.getRole() != null ? user.getRole().getName() : null)
                .build();
    }

    @Transactional
    public HttpStatus avatarWritePermission(Long ownerId, boolean lockTarget, Jwt jwt, Authentication authentication) {
        if (jwt == null) return HttpStatus.UNAUTHORIZED;
        Long actorId;
        try { actorId = Long.parseLong(jwt.getSubject()); }
        catch (NumberFormatException invalid) { return HttpStatus.FORBIDDEN; }

        User actor = userRepository.findWithAccessById(actorId).orElse(null);
        if (actor == null || actor.getStatus() != UserStatus.ACTIVE) {
            return HttpStatus.FORBIDDEN;
        }
        Role actorRole = actor.getRole();
        if (actorRole == null || !Boolean.TRUE.equals(actorRole.getActive())) {
            return HttpStatus.FORBIDDEN;
        }

        User target;
        if (lockTarget) {
            target = userRepository.lockForReview(ownerId).orElse(null);
        } else {
            target = userRepository.findWithAccessById(ownerId).orElse(null);
        }
        if (target == null) return HttpStatus.NOT_FOUND;
        if (target.getStatus() == UserStatus.ARCHIVED) {
            return HttpStatus.CONFLICT;
        }
        if (actorId.equals(ownerId)) return HttpStatus.NO_CONTENT;
        if ("admin".equalsIgnoreCase(actorRole.getName())) return HttpStatus.NO_CONTENT;
        boolean mayUpdate = authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> "PERM:USERS_UPDATE".equals(a.getAuthority()));
        String customerRole = customerRoleName;
        Role targetRole = target.getRole();
        if (mayUpdate && targetRole != null && customerRole.equalsIgnoreCase(targetRole.getName())) {
            return HttpStatus.NO_CONTENT;
        }
        return HttpStatus.FORBIDDEN;
    }

}
