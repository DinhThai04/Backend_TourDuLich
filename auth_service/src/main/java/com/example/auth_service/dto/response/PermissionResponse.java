package com.example.auth_service.dto.response;

import com.example.auth_service.entity.Permission;
import java.util.List;

public record PermissionResponse(Long id, String code, String name, String apiPath, String method, String module,
                                 List<RoleSummary> roles) {
    public record RoleSummary(Long id, String name) {}
    public static PermissionResponse from(Permission p) {
        return new PermissionResponse(p.getId(), p.getCode(), p.getName(), p.getApiPath(), p.getMethod(), p.getModule(),
                p.getRoles() == null ? List.of() : p.getRoles().stream()
                        .map(r -> new RoleSummary(r.getId(), r.getName())).toList());
    }
}
