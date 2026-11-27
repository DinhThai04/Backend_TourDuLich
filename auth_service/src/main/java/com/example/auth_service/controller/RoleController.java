package com.example.auth_service.controller;

import org.springframework.web.bind.annotation.*;
import lombok.RequiredArgsConstructor;

import java.util.List;

import com.example.auth_service.dto.request.RoleRequest;
import com.example.auth_service.dto.response.RoleResponse;
import com.example.auth_service.service.RoleService;
import com.example.auth_service.dto.request.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;

@RequestMapping(path = "/roles")
@RestController
@RequiredArgsConstructor

public class RoleController {
    private final RoleService roleService;

    @PreAuthorize("@accountAccess.can('ROLES_CREATE')")
    @PostMapping("")
    public ApiResponse<RoleResponse> create(@Valid @RequestBody RoleRequest request) throws Exception {
        return ApiResponse.<RoleResponse>builder().code(1000)
                .message("Success").result(RoleResponse.from(roleService.create(request.toRole()))).build();
    }

    @PreAuthorize("@accountAccess.can('ROLES_UPDATE')")
    @PutMapping("")
    public ApiResponse<RoleResponse> update(@Valid @RequestBody RoleRequest request) throws Exception {
        return ApiResponse.<RoleResponse>builder().code(1000).message("Success")
                .result(RoleResponse.from(roleService.update(request.toRole()))).build();
    }

    @PreAuthorize("@accountAccess.can('ROLES_GET_LIST')")
    @GetMapping("")
    public ApiResponse<List<RoleResponse>> getAll() {
        return ApiResponse.<List<RoleResponse>>builder().code(1000).message("Success")
                .result(roleService.fetchAll().stream().map(RoleResponse::from).toList()).build();
    }

    @PreAuthorize("@accountAccess.can('ROLES_GET_DETAIL')")
    @GetMapping("/{id}")
    public ApiResponse<RoleResponse> fetchById(@PathVariable("id") Long id) throws Exception {
        return ApiResponse.<RoleResponse>builder().code(1000).message("Success")
                .result(RoleResponse.from(roleService.fetchRoleById(id))).build();
    }

    @PreAuthorize("@accountAccess.can('ROLES_DELETE')")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable("id") Long id) throws Exception {
        this.roleService.delete(id);
        return ApiResponse.<Void>builder().code(1000).message("Success").build();
    }
}
