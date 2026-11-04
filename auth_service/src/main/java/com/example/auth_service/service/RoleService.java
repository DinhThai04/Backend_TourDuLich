package com.example.auth_service.service;

import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import com.example.auth_service.entity.Role;
import com.example.auth_service.entity.Permission;
import com.example.auth_service.repository.RoleRepository;
import com.example.auth_service.repository.PermissionRepository;
import com.example.auth_service.exception.ErrorCode;
import com.example.auth_service.exception.AppException;



@Service    
@RequiredArgsConstructor
public class RoleService {
    private static final int MAX_REFERENCE_ROWS = 1000;
    /** Names the code or the seed data depends on; a second role with one of them would be ambiguous. */
    private static final Set<String> RESERVED_ROLE_NAMES = Set.of("admin", "customer", "user");

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final AdminContinuity adminContinuity;
    private final com.example.auth_service.repository.UserRepository userRepository;
    private final SessionEpochService sessionEpochService;

    @Value("${app.registration.customer-role-name:Khách Hàng}")
    private String customerRoleName;

    @org.springframework.transaction.annotation.Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Role create(Role role) throws Exception {
        if (role.getId() != null) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "Vai trò mới không được chỉ định ID có sẵn");
        String name = normalizedName(role.getName());
        requireAvailableName(name, null);
        role.setName(name);
        // New roles keep the historic inactive default, but a missing flag must still be stored explicitly.
        if (role.getActive() == null) role.setActive(false);
        if (role.getPermissions() != null) {
            role.setPermissions(resolvePermissions(role.getPermissions()));
        }
        try {
            return this.roleRepository.saveAndFlush(role);
        } catch (org.springframework.dao.DataIntegrityViolationException duplicate) {
            // A concurrent create may pass the pre-check; the unique index is the real arbiter.
            throw new AppException(ErrorCode.ROLE_NAME_ALREADY_EXISTS);
        }
    }

    /**
     * Resolves submitted permission ids strictly. A null entry or an id with no row is caller
     * error: silently dropping it would grant a role fewer permissions than the request asked for.
     */
    private List<Permission> resolvePermissions(List<Permission> requested) {
        if (requested.stream().anyMatch(permission -> permission == null || permission.getId() == null)) {
            throw new AppException(ErrorCode.PERMISSION_NOT_FOUND);
        }
        List<Long> ids = requested.stream().map(Permission::getId).distinct().toList();
        List<Permission> dbPermissions = this.permissionRepository.findByIdIn(ids);
        if (dbPermissions.size() != ids.size()) throw new AppException(ErrorCode.PERMISSION_NOT_FOUND);
        return dbPermissions;
    }

    /** Trims the display name and rejects blank values supplied through the service directly. */
    private static String normalizedName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "Tên vai trò không được để trống");
        }
        return name.trim();
    }

    private boolean isReserved(String name) {
        if (name == null) return false;
        return RESERVED_ROLE_NAMES.contains(name.toLowerCase(Locale.ROOT))
                || customerRoleName != null && customerRoleName.equalsIgnoreCase(name);
    }

    /**
     * Enforces case-insensitive uniqueness and protects the built-in names. A built-in role may
     * keep its own name, but neither a new role nor an update may take it, and a built-in role
     * cannot be renamed because login, registration and admin continuity depend on the name.
     */
    private void requireAvailableName(String name, Role currentRole) {
        String currentName = currentRole == null ? null : normalizedName(currentRole.getName());
        boolean keepsCurrentName = currentName != null && currentName.equalsIgnoreCase(name);
        if (isReserved(name)) {
            if (!keepsCurrentName) throw new AppException(ErrorCode.ROLE_NAME_RESERVED);
            return;
        }
        if (isReserved(currentName) && !keepsCurrentName) {
            throw new AppException(ErrorCode.ROLE_NAME_RESERVED);
        }
        Long currentId = currentRole == null ? null : currentRole.getId();
        roleRepository.findByNameIgnoreCase(name)
                .filter(existing -> !existing.getId().equals(currentId))
                .ifPresent(existing -> {
                    throw new AppException(ErrorCode.ROLE_NAME_ALREADY_EXISTS);
                });
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public Role fetchRoleById(Long id) throws Exception {
        Optional<Role> role = this.roleRepository.findById(id);
        if (role.isPresent()) {
            // The controller serializes this entity after the transaction closes.
            org.hibernate.Hibernate.initialize(role.get().getPermissions());
            return role.get();
        } else {
            throw new AppException(ErrorCode.ROLE_NOT_FOUND);
        }   
    }

    @org.springframework.transaction.annotation.Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Role update(Role role) throws Exception {
        if (role.getId() == null) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "Thiếu ID vai trò cần cập nhật");
        adminContinuity.lockRoles(role.getId());
        Role currentRole = this.fetchRoleById(role.getId());
        adminContinuity.refresh(currentRole);
        String name = normalizedName(role.getName());
        // An omitted flag keeps the stored value instead of deactivating the role (and locking its users out).
        boolean active = role.getActive() != null ? role.getActive() : Boolean.TRUE.equals(currentRole.getActive());
        adminContinuity.requireRoleChange(role.getId(), name, active);
        requireAvailableName(name, currentRole);

        String previousName = currentRole.getName();
        boolean previousActive = Boolean.TRUE.equals(currentRole.getActive());
        java.util.Set<Long> previousPermissions = permissionIds(currentRole);
        if (role.getPermissions() != null) {
            currentRole.setPermissions(resolvePermissions(role.getPermissions()));
        }

        currentRole.setName(name);
        currentRole.setActive(active);
        currentRole.setDescription(role.getDescription());
        boolean authorizationChanged = !name.equals(previousName) || active != previousActive
                || (role.getPermissions() != null && !permissionIds(currentRole).equals(previousPermissions));

        Role saved;
        try {
            saved = this.roleRepository.saveAndFlush(currentRole);
        } catch (org.springframework.dao.DataIntegrityViolationException duplicate) {
            throw new AppException(ErrorCode.ROLE_NAME_ALREADY_EXISTS);
        }
        // Tokens carry the role name and permissions, so an old session would keep the previous
        // access for up to an hour. Revoke every session of the role's accounts in this transaction.
        if (authorizationChanged) {
            sessionEpochService.revokeAll(userRepository.findIdsByRoleId(role.getId()),
                    "Quyền của vai trò \"" + name + "\" đã thay đổi, đã thu hồi phiên đăng nhập cũ");
        }
        return saved;
    }

    private static java.util.Set<Long> permissionIds(Role role) {
        return role.getPermissions() == null ? java.util.Set.of()
                : role.getPermissions().stream().map(Permission::getId).collect(java.util.stream.Collectors.toSet());
    }

    @org.springframework.transaction.annotation.Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void delete(Long id) throws Exception {
        adminContinuity.lockRoles(id);
        Role role = this.fetchRoleById(id);
        adminContinuity.refresh(role);
        adminContinuity.requireRoleChange(id, null, false);
        adminContinuity.requireUnusedRole(id);

        this.roleRepository.delete(role);
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public List<Role> fetchAll() {
        // Reference data is small, but a hard cap keeps a bad seed from exhausting memory.
        List<Role> roles = roleRepository.findAll(org.springframework.data.domain.PageRequest.of(0, MAX_REFERENCE_ROWS)).getContent();
        // The controller serializes these entities after the transaction closes.
        roles.forEach(role -> org.hibernate.Hibernate.initialize(role.getPermissions()));
        return roles;
    }

}
