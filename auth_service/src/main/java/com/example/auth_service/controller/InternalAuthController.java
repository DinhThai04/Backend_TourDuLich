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
}