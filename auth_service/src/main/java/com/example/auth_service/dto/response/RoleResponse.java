package com.example.auth_service.dto.response;

import com.example.auth_service.entity.Permission;
import com.example.auth_service.entity.Role;

import java.util.List;

public record RoleResponse(Long id, String name, String description, Boolean active, List<RolePermission> permissions) {
    public record RolePermission(Long id, String code, String name, String apiPath, String method, String module) {
        private static RolePermission from(Permission permission) {
            return new RolePermission(permission.getId(), permission.getCode(), permission.getName(),
                    permission.getApiPath(), permission.getMethod(), permission.getModule());
        }
    }

    public static RoleResponse from(Role role) {
        return new RoleResponse(role.getId(), role.getName(), role.getDescription(), role.getActive(),
                role.getPermissions() == null ? null : role.getPermissions().stream().map(RolePermission::from).toList());
    }
}
