package com.example.auth_service.dto.request;
import jakarta.validation.constraints.*;
public record AccountActionRequest(@NotBlank @Size(max=1000) String reason) {}
