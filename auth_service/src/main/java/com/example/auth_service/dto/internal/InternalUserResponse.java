package com.example.auth_service.dto.internal;

import lombok.*;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InternalUserResponse {
    private Long id;
    private String email;
    private String phone;
    private String avatar;
    private String username;
    private String status;
    private String role;
}