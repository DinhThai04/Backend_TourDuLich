package com.example.auth_service.controller;

import java.text.ParseException;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import com.example.auth_service.dto.request.ApiResponse;
import com.example.auth_service.dto.request.AuthenticationRequest;
import com.example.auth_service.dto.request.ChangePasswordRequest;
import com.example.auth_service.dto.request.IntrospectRequest;
import com.example.auth_service.dto.request.LogoutRequest;
import com.example.auth_service.dto.request.RefreshRequest;
import com.example.auth_service.dto.response.AuthenticationResponse;
import com.example.auth_service.dto.response.IntrospectResponse;
import com.example.auth_service.service.AuthService;
import com.nimbusds.jose.JOSEException;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
        
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class AuthController {
    AuthService authService;
    @PostMapping("/login")
    ApiResponse<AuthenticationResponse>  login (@RequestBody @Valid AuthenticationRequest request){
        var result = authService.authenticated(request);
        return ApiResponse.<AuthenticationResponse>builder()
                .result(result).build();
    }

    @PostMapping("/introspect")
    ApiResponse<IntrospectResponse> authenticate(@RequestBody IntrospectRequest request) {
        var result = authService.introspect(request);
        return ApiResponse.<IntrospectResponse>builder().result(result).build();
    }

     @PostMapping("/refresh")
    ApiResponse<AuthenticationResponse> authenticate(@RequestBody RefreshRequest request)
            throws ParseException, JOSEException {
        var result = authService.refreshToken(request);
        return ApiResponse.<AuthenticationResponse>builder().result(result).build();
    }

    @PostMapping("/logout")
    ApiResponse<Void> logout(@RequestBody LogoutRequest request) throws ParseException, JOSEException {
        authService.logout(request);
        return ApiResponse.<Void>builder().build();
    }

    @PostMapping("/Change-password")
    ApiResponse<Void> changePassword(@RequestBody ChangePasswordRequest request) throws ParseException, JOSEException {
        authService.changePassword(request);
        return ApiResponse.<Void>builder().message("Change password successfully").build();
    }

    @PostMapping("/google")
    ApiResponse<AuthenticationResponse>  loginWithGG(@RequestHeader("Authorization") String header) throws Exception {
        var result = authService.authenticatedWithGG(header);
        return ApiResponse.<AuthenticationResponse>builder()
                .result(result).build();
    }




}
