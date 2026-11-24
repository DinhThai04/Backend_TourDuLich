package com.example.file_service.dto.internal;

import lombok.*;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerifyFileResponse {
    private UUID fileId;
    private boolean exists;
    private String ownerType;
    private String ownerId;
    private String url;
    private boolean canDelete;
}