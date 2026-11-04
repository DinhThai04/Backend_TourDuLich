package com.example.auth_service.service;

import com.example.auth_service.dto.request.PermissionRequest;
import com.example.auth_service.dto.response.PermissionResponse;
import com.example.auth_service.entity.Permission;
import com.example.auth_service.entity.Role;
import com.example.auth_service.repository.PermissionRepository;
import com.example.auth_service.repository.RoleRepository;
import com.example.auth_service.exception.AppException;
import com.example.auth_service.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@Transactional
@RequiredArgsConstructor
public class PermissionService {
    private static final int MAX_REFERENCE_ROWS = 1000;

    private final PermissionRepository permissionRepository;
    private final RoleRepository roleRepository;
    private final com.example.auth_service.repository.UserRepository userRepository;
    private final SessionEpochService sessionEpochService;

    public PermissionResponse create(PermissionRequest request) {
        if (request.getId() != null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Quyền mới không có ID");
        validateUnique(request, -1L);
        Permission p = new Permission();
        p.setCode(requireCode(request.getCode()));
        if (permissionRepository.existsByCodeAndIdNot(p.getCode(), -1L))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Mã quyền đã tồn tại");
        copy(request, p);
        p.setRoles(new ArrayList<>());
        try {
            permissionRepository.saveAndFlush(p);
        } catch (org.springframework.dao.DataIntegrityViolationException duplicate) {
            // A concurrent create can slip past the pre-check; the unique code index is the arbiter.
            throw new AppException(ErrorCode.PERMISSION_ALREADY_EXISTS);
        }
        assignRoles(p, request.getRoleIds());
        return PermissionResponse.from(p);
    }

    public PermissionResponse update(PermissionRequest request) {
        if (request.getId() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Thiếu ID quyền cần sửa");
        Permission p = find(request.getId());
        validateUnique(request, p.getId());
        // The code is the authorization key already embedded in issued tokens; it must stay immutable.
        Set<Long> rolesBefore = roleIds(p);
        copy(request, p);
        assignRoles(p, request.getRoleIds());
        revokeRolesThatLostThePermission(p, rolesBefore);
        return PermissionResponse.from(permissionRepository.save(p));
    }

    @Transactional(readOnly = true)
    public PermissionResponse fetchPermissionById(Long id) { return PermissionResponse.from(find(id)); }

    @Transactional(readOnly = true)
    public List<PermissionResponse> fetchAll() {
        // Reference data is small, but a hard cap keeps a bad seed from exhausting memory.
        return permissionRepository.findAll(org.springframework.data.domain.PageRequest.of(0, MAX_REFERENCE_ROWS))
                .stream().map(PermissionResponse::from).toList();
    }

    public void delete(Long id) {
        Permission p = find(id);
        Set<Long> rolesBefore = roleIds(p);
        assignRoles(p, List.of());
        revokeRolesThatLostThePermission(p, rolesBefore);
        permissionRepository.delete(p);
    }

    /**
     * Tokens carry the permission codes, so an account in a role that just lost this code would keep
     * the authority until the token expires. Newly granted roles need nothing: the next refresh
     * already issues the added authority.
     */
    private void revokeRolesThatLostThePermission(Permission permission, Set<Long> rolesBefore) {
        Set<Long> lost = new HashSet<>(rolesBefore);
        lost.removeAll(roleIds(permission));
        if (lost.isEmpty()) return;
        sessionEpochService.revokeAll(userRepository.findIdsByRoleIds(lost),
                "Quyền \"" + permission.getCode() + "\" đã bị gỡ khỏi vai trò, đã thu hồi phiên đăng nhập cũ");
    }

    private static Set<Long> roleIds(Permission permission) {
        return permission.getRoles() == null ? Set.of()
                : permission.getRoles().stream().map(Role::getId).collect(java.util.stream.Collectors.toSet());
    }

    private Permission find(Long id) {
        return permissionRepository.findById(id).orElseThrow(() -> new AppException(ErrorCode.PERMISSION_NOT_FOUND));
    }

    private String requireCode(String raw) {
        if (raw == null || raw.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mã quyền là bắt buộc");
        String code = raw.trim().toUpperCase(java.util.Locale.ROOT);
        if (!code.matches("[A-Z][A-Z0-9_]{1,99}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mã quyền không hợp lệ");
        return code;
    }

    private void copy(PermissionRequest request, Permission p) {
        p.setName(request.getName().trim());
        p.setApiPath(request.getApiPath().trim());
        p.setMethod(request.getMethod());
        p.setModule(request.getModule().trim());
    }

    private void validateUnique(PermissionRequest request, Long id) {
        if (permissionRepository.existsByNameAndIdNot(request.getName().trim(), id)
                || permissionRepository.existsByApiPathAndMethodAndIdNot(request.getApiPath().trim(), request.getMethod(), id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Tên quyền hoặc API và phương thức đã tồn tại");
        }
    }

    private void assignRoles(Permission p, List<Long> requested) {
        if (requested == null) return;
        var ids = new HashSet<>(requested);
        List<Role> roles = roleRepository.findAllById(ids);
        if (roles.size() != ids.size()) throw new AppException(ErrorCode.ROLE_NOT_FOUND);
        if (p.getRoles() != null) {
            for (Role old : new ArrayList<>(p.getRoles())) old.getPermissions().removeIf(item -> item.getId().equals(p.getId()));
        }
        for (Role role : roles) {
            if (role.getPermissions() == null) role.setPermissions(new ArrayList<>());
            if (role.getPermissions().stream().noneMatch(item -> item.getId().equals(p.getId()))) role.getPermissions().add(p);
        }
        p.setRoles(new ArrayList<>(roles));
    }
}
