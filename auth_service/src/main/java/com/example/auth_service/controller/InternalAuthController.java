package com.example.auth_service.controller;

import com.example.auth_service.dto.internal.InternalUserResponse;
import com.example.auth_service.entity.User;
import com.example.auth_service.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/internal/auth/users")
@RequiredArgsConstructor
public class InternalAuthController {

    private final UserRepository userRepository;

    @GetMapping("/{id}")
    public ResponseEntity<InternalUserResponse> getUserById(@PathVariable Long id) {
        return userRepository.findById(id)
                .map(this::toInternalResponse)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/status")
    public ResponseEntity<Map<String, Object>> getUserStatus(@PathVariable Long id) {
        return userRepository.findById(id)
                .map(user -> {
                    Map<String, Object> map = new java.util.HashMap<>();
                    map.put("id", user.getId());
                    map.put("status", user.getStatus().name());
                    return ResponseEntity.ok(map);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/batch")
    public ResponseEntity<List<InternalUserResponse>> getUsersBatch(@RequestBody List<Long> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 100 || ids.stream().anyMatch(id -> id == null || id < 1)) {
            return ResponseEntity.badRequest().build();
        }
        List<User> users = userRepository.findAllById(ids.stream().distinct().toList());
        List<InternalUserResponse> responses = users.stream()
                .map(this::toInternalResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(responses);
    }

    private InternalUserResponse toInternalResponse(User user) {
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

    @org.springframework.transaction.annotation.Transactional
    @PostMapping("/users/{ownerId}/avatar-write-permission")
    public ResponseEntity<Void> checkAvatarWritePermission(
            @PathVariable Long ownerId,
            @RequestParam(defaultValue = "false") boolean lockTarget,
            @org.springframework.security.core.annotation.AuthenticationPrincipal org.springframework.security.oauth2.jwt.Jwt jwt,
            org.springframework.security.core.Authentication authentication) {
        if (jwt == null) return ResponseEntity.status(401).build();
        Long actorId;
        try { actorId = Long.parseLong(jwt.getSubject()); }
        catch (NumberFormatException invalid) { return ResponseEntity.status(403).build(); }

        User actor = userRepository.findWithAccessById(actorId).orElse(null);
        if (actor == null || actor.getStatus() != com.example.auth_service.entity.UserStatus.ACTIVE) {
            return ResponseEntity.status(403).build();
        }
        com.example.auth_service.entity.Role actorRole = actor.getRole();
        if (actorRole == null || !Boolean.TRUE.equals(actorRole.getActive())) {
            return ResponseEntity.status(403).build();
        }

        User target;
        if (lockTarget) {
            target = userRepository.lockForReview(ownerId).orElse(null);
        } else {
            target = userRepository.findWithAccessById(ownerId).orElse(null);
        }
        if (target == null) return ResponseEntity.status(404).build();
        if (target.getStatus() == com.example.auth_service.entity.UserStatus.ARCHIVED) {
            return ResponseEntity.status(409).build();
        }
        if (actorId.equals(ownerId)) return ResponseEntity.noContent().build();
        if ("admin".equalsIgnoreCase(actorRole.getName())) return ResponseEntity.noContent().build();
        boolean mayUpdate = authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> "PERM:USERS_UPDATE".equals(a.getAuthority()));
        String customerRole = customerRoleName;
        com.example.auth_service.entity.Role targetRole = target.getRole();
        if (mayUpdate && targetRole != null && customerRole.equalsIgnoreCase(targetRole.getName())) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.status(403).build();
    }

    @org.springframework.beans.factory.annotation.Value("${app.registration.customer-role-name:Khách Hàng}")
    private String customerRoleName;
}
