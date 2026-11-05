package com.example.auth_service.controller;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import lombok.RequiredArgsConstructor;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import com.example.auth_service.entity.Role;
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
    public ApiResponse<Role> create(@Valid @RequestBody Role role) throws Exception {
        return ApiResponse.<Role>builder().code(1000)
                .message("Success").result(this.roleService.create(role)).build();
    }

    @PreAuthorize("@accountAccess.can('ROLES_UPDATE')")
    @PutMapping("")
    public ApiResponse<Role> update(@Valid @RequestBody Role role) throws Exception {
        return ApiResponse.<Role>builder().code(1000).message("Success")
                .result(this.roleService.update(role)).build();
    }

    @PreAuthorize("@accountAccess.can('ROLES_GET_LIST')")
    @GetMapping("")
    public ApiResponse<List<Role>> getAll(
            ) {
        return ApiResponse.<List<Role>>builder().code(1000).message("Success")
                .result(this.roleService.fetchAll()).build();
    }

    @PreAuthorize("@accountAccess.can('ROLES_GET_DETAIL')")
    @GetMapping("/{id}")
    public ApiResponse<Role> fetchById(@PathVariable("id") Long id) throws Exception {
        return ApiResponse.<Role>builder().code(1000).message("Success")
                .result(this.roleService.fetchRoleById(id)).build();
    }

    @PreAuthorize("@accountAccess.can('ROLES_DELETE')")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable("id") Long id) throws Exception {
        this.roleService.delete(id);
        return ApiResponse.<Void>builder().code(1000).message("Success").build();
    }
}