package com.example.auth_service.controller;

import java.io.IOException;
import java.util.List;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.example.auth_service.dto.request.ApiResponse;
import com.example.auth_service.dto.request.UserCreationRequest;
import com.example.auth_service.dto.request.RegisterRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import com.example.auth_service.dto.request.UserUpdate;
import com.example.auth_service.dto.response.FileResponse;
import com.example.auth_service.dto.response.UserResponse;
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

    @GetMapping("/display-names")
    @PreAuthorize("@accountAccess.can('USERS_GET_LIST') or @accountAccess.can('BOOKINGS_GET_ALL') or @accountAccess.can('INVOICES_GET_ALL')")
    public ApiResponse<java.util.Map<Long, String>> displayNames(@RequestParam java.util.List<Long> ids) {
        return ApiResponse.<java.util.Map<Long, String>>builder().result(userService.displayNames(ids)).build();
    }

    @PostMapping("/register")
    public ApiResponse<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.<UserResponse>builder()
                .result(userService.registerCustomer(request))
                .build();
    }

    @PostMapping("")
    @PreAuthorize("@userService.canCreateManagedUser(authentication)")
    public ApiResponse<UserResponse> createUser(@Valid @RequestBody UserCreationRequest request) {
        return ApiResponse.<UserResponse>builder()
                .result(userService.createUser(request))
                .build();
    }

    @PreAuthorize("@accountAccess.can('USERS_GET_LIST')")
    @GetMapping("/list")
    public ApiResponse<List<UserResponse>> listUser(
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size) {
        return ApiResponse.<List<UserResponse>>builder()
                .result(userService.getAllUser(page, size))
                .build();
    }

    @PreAuthorize("@accountAccess.owns(#id) or @accountAccess.can('USERS_GET_DETAIL')")
    @GetMapping("/{id}")
    public ApiResponse<UserResponse> getUserById(@PathVariable Long id) {
        return ApiResponse.<UserResponse>builder()
                .result(userService.getUserById(id))
                .build();
    }

    @PreAuthorize("@accountAccess.owns(#id) or @accountAccess.can('USERS_UPDATE')")
    @PostMapping("/UpdateUser/{id}")
    public ApiResponse<UserResponse> updateUser(
            @PathVariable Long id,
            @jakarta.validation.Valid @ModelAttribute UserUpdate request) {

        return ApiResponse.<UserResponse>builder().result(userService.updateUser(id, request))
                .build();
    }


    @PreAuthorize("@accountAccess.owns(#id) or @accountAccess.can('USERS_UPDATE')")
    @PostMapping ("/updateAvtUser/{id}")
    public ApiResponse<FileResponse>  updateAvtUser( @PathVariable("id") String id,
            @RequestPart("file") MultipartFile file) throws IOException{
                 FileResponse response = userService.uploadAvatar(id, file);
        return ApiResponse.<FileResponse>builder().result(response).build();
    }

    @PreAuthorize("@accountAccess.can('USERS_DELETE')")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteUser(@PathVariable Long id, @RequestParam(required = false) String reason) {
        userService.deleteUser(id, reason);
        return ApiResponse.<Void>builder().build();
    }
 
    @PutMapping("/{id}/status")
    @PreAuthorize("@accountAccess.can('USER_STATUS_UPDATE')")
    public ApiResponse<UserResponse> status(@PathVariable Long id,@Valid @RequestBody com.example.auth_service.dto.request.AccountStatusRequest request){
        return ApiResponse.<UserResponse>builder().result(userService.changeStatus(id,request.status(),request.reason())).build();
    }
    @PostMapping("/{id}/archive")
    @PreAuthorize("@accountAccess.can('USER_ARCHIVE')")
    public ApiResponse<UserResponse> archive(@PathVariable Long id,@Valid @RequestBody com.example.auth_service.dto.request.AccountActionRequest request){
        return ApiResponse.<UserResponse>builder().result(userService.archive(id,request.reason())).build();
    }
    @GetMapping("/{id}/events")
    @PreAuthorize("@accountAccess.can('USER_ACCOUNT_EVENTS')")
    public ApiResponse<java.util.List<com.example.auth_service.entity.UserAccountEvent>> accountEvents(@PathVariable Long id){
        return ApiResponse.<java.util.List<com.example.auth_service.entity.UserAccountEvent>>builder().result(userService.accountEvents(id)).build();
    }
}
