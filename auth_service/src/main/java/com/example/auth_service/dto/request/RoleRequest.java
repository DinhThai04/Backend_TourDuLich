package com.example.auth_service.dto.request;

import com.example.auth_service.entity.Permission;
import com.example.auth_service.entity.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record RoleRequest(
        Long id,
        @NotBlank(message = "Name is required")
        @Size(max = 255, message = "Tên vai trò tối đa 255 ký tự") String name,
        @Size(max = 255, message = "Mô tả vai trò tối đa 255 ký tự") String description,
        Boolean active,
        List<PermissionReference> permissions) {

    public record PermissionReference(Long id) {}

    public Role toRole() {
        var role = new Role();
        role.setId(id);
        role.setName(name);
        role.setDescription(description);
        role.setActive(active);
        if (permissions != null) {
            role.setPermissions(permissions.stream()
                    .map(permission -> permission == null ? null : Permission.builder().id(permission.id()).build())
                    .toList());
        }
        return role;
    }
}
