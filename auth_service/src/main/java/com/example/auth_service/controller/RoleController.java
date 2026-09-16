package com.example.auth_service.controller;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;

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

    @PostMapping("")
    public ApiResponse<Role> create(@Valid @RequestBody Role role) throws Exception {
        return ApiResponse.<Role>builder().code(HttpStatus.CREATED.value())
                .message(HttpStatus.CREATED.getReasonPhrase()).result(this.roleService.create(role)).build();
    }

    @PutMapping("")
    public ApiResponse<Role> update(@Valid @RequestBody Role role) throws Exception {
        return ApiResponse.<Role>builder().code(HttpStatus.OK.value()).message(HttpStatus.OK.getReasonPhrase())
                .result(this.roleService.update(role)).build();
    }

    @GetMapping("")
    public ApiResponse<List<Role>> getAll(
            ) {
        return ApiResponse.<List<Role>>builder().code(HttpStatus.OK.value()).message(HttpStatus.OK.getReasonPhrase())
                .result(this.roleService.fetchAll()).build();
    }

    @GetMapping("/{id}")
    public ApiResponse<Role> fetchById(@PathVariable("id") Long id) throws Exception {
        return ApiResponse.<Role>builder().code(HttpStatus.OK.value()).message(HttpStatus.OK.getReasonPhrase())
                .result(this.roleService.fetchRoleById(id)).build();
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable("id") Long id) throws Exception {
        this.roleService.delete(id);
        return ApiResponse.<Void>builder().code(HttpStatus.OK.value()).message(HttpStatus.OK.getReasonPhrase()).build();
    }
}