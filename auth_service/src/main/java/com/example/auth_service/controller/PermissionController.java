package com.example.auth_service.controller;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;

import java.util.List;

import com.example.auth_service.dto.request.ApiResponse;
import com.example.auth_service.entity.Permission;
import com.example.auth_service.service.PermissionService;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
    
@RestController
@RequestMapping("/permissions")

@RequiredArgsConstructor

public class PermissionController {
    private final PermissionService permissionService;

    @PostMapping("")
    public ApiResponse<Permission> create(@Valid @RequestBody Permission permission) throws Exception {
        return ApiResponse.<Permission>builder().code(HttpStatus.CREATED.value())
                .message(HttpStatus.CREATED.getReasonPhrase()).result(this.permissionService.create(permission))
                .build();
    }

    @PutMapping("")
    public ApiResponse<Permission> update(@Valid @RequestBody Permission permission) throws Exception {
        return ApiResponse.<Permission>builder().code(HttpStatus.OK.value()).message(HttpStatus.OK.getReasonPhrase())
                .result(this.permissionService.update(permission)).build();
    }

    @GetMapping("/list")
    public ApiResponse<List<Permission>> getAll() {
        return ApiResponse.<List<Permission>>builder().code(HttpStatus.OK.value())
                .message(HttpStatus.OK.getReasonPhrase()).result(this.permissionService.fetchAll()).build();
    }

    @GetMapping("/{id}")
    public ApiResponse<Permission> fetchById(@PathVariable("id") Long id) throws Exception {
        return ApiResponse.<Permission>builder().code(HttpStatus.OK.value()).message(HttpStatus.OK.getReasonPhrase())
                .result(this.permissionService.fetchPermissionById(id)).build();
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable("id") Long id) throws Exception {
        this.permissionService.delete(id);
        return ApiResponse.<Void>builder().code(HttpStatus.OK.value()).message(HttpStatus.OK.getReasonPhrase()).build();
    }
}