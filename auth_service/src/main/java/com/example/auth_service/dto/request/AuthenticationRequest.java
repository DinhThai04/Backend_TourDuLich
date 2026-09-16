package com.example.auth_service.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;   
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AuthenticationRequest {

    // Đặt message là tên của Enum
    @NotBlank(message = "EMAIL_IS_REQUIRED")
    @Email(message = "INVALID_EMAIL_FORMAT") // (Bạn cần tạo Enum này nếu chưa có)
    private String email;

    @NotBlank(message = "PASSWORD_IS_REQUIRED") // (Ví dụ cho password)
    private String password;

}