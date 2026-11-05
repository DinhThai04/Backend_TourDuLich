package com.example.auth_service.dto.request;

import lombok.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.experimental.FieldDefaults;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class ChangePasswordRequest {
    @NotBlank @Size(max = 72)
    String oldPassword;
    @NotBlank @Size(min = 8, max = 72)
    String newPassword;
    @NotBlank @Size(min = 8, max = 72)
    String confirmPassword;

}
