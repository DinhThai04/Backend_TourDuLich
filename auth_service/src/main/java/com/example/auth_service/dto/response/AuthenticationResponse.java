package com.example.auth_service.dto.response;

import java.util.Date;

import com.example.auth_service.entity.User;

import lombok.*;
import lombok.experimental.FieldDefaults;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AuthenticationResponse {
    String token;
    Date expiryTime;
    UserResponse user;
}
