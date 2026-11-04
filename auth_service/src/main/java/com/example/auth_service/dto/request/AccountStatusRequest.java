package com.example.auth_service.dto.request;
import jakarta.validation.constraints.*;
import com.example.auth_service.entity.UserStatus;
public record AccountStatusRequest(@NotNull UserStatus status,@NotBlank @Size(max=1000) String reason) {}
