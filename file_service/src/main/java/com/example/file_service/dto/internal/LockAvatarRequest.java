package com.example.file_service.dto.internal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.*;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LockAvatarRequest {
    @NotNull
    private UUID fileId;
    
    @NotBlank
    private String url;
    
    @NotBlank
    private String contentType;
    
    @Positive
    private long size;
}