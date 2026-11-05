package com.example.auth_service.controller;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import lombok.RequiredArgsConstructor;

import java.util.List;

import com.example.auth_service.dto.request.ApiResponse;
import com.example.auth_service.dto.request.PermissionRequest;
import com.example.auth_service.dto.response.PermissionResponse;
import com.example.auth_service.service.PermissionService;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
    
@RestController
@RequestMapping("/permissions")

@RequiredArgsConstructor

public class PermissionController {
    private final PermissionService permissionService;

    @PreAuthorize("@accountAccess.can('PERMISSIONS_CREATE') and (#permission.roleIds == null or @accountAccess.can('ROLES_UPDATE'))")
    @PostMapping("")
    public ApiResponse<PermissionResponse> create(@Valid @RequestBody PermissionRequest permission) throws Exception {
        return ApiResponse.<PermissionResponse>builder().code(1000)
                .message("Success").result(this.permissionService.create(permission))
                .build();
    }

    @PreAuthorize("@accountAccess.can('PERMISSIONS_UPDATE') and @accountAccess.can('ROLES_UPDATE')")
    @PutMapping("")
    public ApiResponse<PermissionResponse> update(@Valid @RequestBody PermissionRequest permission) throws Exception {
        return ApiResponse.<PermissionResponse>builder().code(1000).message("Success")
                .result(this.permissionService.update(permission)).build();
    }

    @PreAuthorize("@accountAccess.can('PERMISSIONS_GET_LIST')")
    @GetMapping("/list")
    public ApiResponse<List<PermissionResponse>> getAll() {
        return ApiResponse.<List<PermissionResponse>>builder().code(1000)
                .message("Success").result(this.permissionService.fetchAll()).build();
    }

    @PreAuthorize("@accountAccess.can('PERMISSIONS_GET_DETAIL')")
    @GetMapping("/{id}")
    public ApiResponse<PermissionResponse> fetchById(@PathVariable("id") Long id) throws Exception {
        return ApiResponse.<PermissionResponse>builder().code(1000).message("Success")
                .result(this.permissionService.fetchPermissionById(id)).build();
    }

    @PreAuthorize("@accountAccess.can('PERMISSIONS_DELETE') and @accountAccess.can('ROLES_UPDATE')")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable("id") Long id) throws Exception {
        this.permissionService.delete(id);
        return ApiResponse.<Void>builder().code(1000).message("Success").build();
    }
}