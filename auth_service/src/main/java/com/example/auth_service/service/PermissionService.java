package com.example.auth_service.service;

import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import com.example.auth_service.entity.Permission;
import com.example.auth_service.entity.Role;
import com.example.auth_service.repository.PermissionRepository;
import com.example.auth_service.repository.RoleRepository;
import com.example.auth_service.exception.ErrorCode;
import com.example.auth_service.exception.AppException;

@Service
@RequiredArgsConstructor
public class PermissionService {
    private final PermissionRepository permissionRepository;

    public Permission create(Permission permission) throws Exception {
        if (this.permissionRepository.existsByModuleAndApiPathAndMethod(
                permission.getModule(),
                permission.getApiPath(),
                permission.getMethod())) {
            throw new DataIntegrityViolationException("Permission already exists");
        }
        return this.permissionRepository.save(permission);
    }

    public Permission fetchPermissionById(Long id) throws Exception {
        Optional<Permission> permission = this.permissionRepository.findById(id);
        if (permission.isPresent()) {
            return permission.get();
        } else {
            throw new AppException(ErrorCode.PERMISSION_NOT_FOUND);
        }
    }   

    public Permission update(Permission permission) throws Exception {
        Permission currentPermission = this.fetchPermissionById(permission.getId());

        if (this.permissionRepository.existsByModuleAndApiPathAndMethod(
                permission.getModule(),
                permission.getApiPath(),
                permission.getMethod())) {
            throw new DataIntegrityViolationException("Permission already exists");
        }

        if (this.permissionRepository.existsByName(permission.getName())) {
            throw new DataIntegrityViolationException("Permission name already exists");
        }

        currentPermission.setApiPath(permission.getApiPath());
        currentPermission.setName(permission.getName());
        currentPermission.setModule(permission.getModule());
        currentPermission.setMethod(permission.getMethod());

        return this.permissionRepository.save(currentPermission);
    }

    public void delete(Long id) throws Exception {
        Permission permission = this.fetchPermissionById(id);

        permission.getRoles().forEach(
                role -> role.getPermissions().remove(permission));
        this.permissionRepository.delete(permission);
    }

    public List<Permission> fetchAll() {
        return permissionRepository.findAll();
    }
}