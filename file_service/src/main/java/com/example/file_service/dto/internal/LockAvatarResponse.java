package com.example.file_service.dto.internal;

import lombok.*;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LockAvatarResponse {
    private UUID fileId;
    private String avatarOwnerId;
    private String url;
    private Long avatarVersion;
    private boolean locked;
}
