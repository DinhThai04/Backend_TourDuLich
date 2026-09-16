package com.example.auth_service.controller;

import java.io.IOException;
import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.method.P;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.example.auth_service.dto.request.ApiResponse;
import com.example.auth_service.dto.request.UserCreationRequest;
import com.example.auth_service.dto.request.UserUpdate;
import com.example.auth_service.dto.response.FileResponse;
import com.example.auth_service.dto.response.PageResponse;
import com.example.auth_service.dto.response.UserResponse;
import com.example.auth_service.entity.User;
import com.example.auth_service.service.UserService;

import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
@Validated
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class UserController {

    UserService userService;

    @PostMapping("/register")
        ApiResponse<UserResponse> createUser(@RequestBody UserCreationRequest request) {
        return ApiResponse.<UserResponse>builder()
                .result(userService.createUser(request))
                .build();
    }

    @GetMapping("/list")
    public ApiResponse<List<UserResponse>> listUser() {
        return ApiResponse.<List<UserResponse>>builder()
                .result(userService.getAllUser())
                .build();
    }
    // ApiResponse<PageResponse<UserResponse>> listUser(
    //         @RequestParam(value = "page", required = false, defaultValue = "1") int page,
    //         @RequestParam(value = "size", required = false, defaultValue = "10") int size
    //         ){
    //     return ApiResponse.<PageResponse<UserResponse>>builder()
    //             .result(userService.getAllUser(page, size))
    //             .build();
    // }

    @GetMapping("/{id}")
    public ApiResponse<UserResponse> getUserById(@PathVariable Long id) {
        return ApiResponse.<UserResponse>builder()
                .result(userService.getUserById(id))
                .build();
    }

    @PostMapping("/UpdateUser/{id}")
    public ApiResponse<UserResponse> updateUser(
            @PathVariable Long id,
            @ModelAttribute UserUpdate request) {

        return ApiResponse.<UserResponse>builder().result(userService.updateUser(id, request))
                .build();
    }


    @PostMapping ("/updateAvtUser/{id}")
    public ApiResponse<FileResponse>  updateAvtUser( @PathVariable("id") String id,
            @RequestPart("file") MultipartFile file) throws IOException{
                 FileResponse response = userService.uploadAvatar(id, file);
        return ApiResponse.<FileResponse>builder().result(response).build();
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteUser(@PathVariable Long id) {
        userService.deleteUser(id);
        return ApiResponse.<Void>builder().build();
    }
 
}
