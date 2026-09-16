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
import com.example.auth_service.entity.Role;
import com.example.auth_service.entity.Permission;
import com.example.auth_service.repository.RoleRepository;
import com.example.auth_service.repository.PermissionRepository;
import com.example.auth_service.exception.ErrorCode;
import com.example.auth_service.exception.AppException;



@Service    
@RequiredArgsConstructor
public class RoleService {
    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;

    public Role create(Role role) throws Exception {
        if (this.roleRepository.existsByName(
                role.getName())) {
            throw new DataIntegrityViolationException("Role already exists");
        }
        if (role.getPermissions() != null) {
            List<Long> reqPermissions = role.getPermissions()
                    .stream().map(
                            permission -> permission.getId())
                    .collect(Collectors.toList());
            List<Permission> dbPermissions = this.permissionRepository.findByIdIn(reqPermissions);
            role.setPermissions(dbPermissions);
        }
        return this.roleRepository.save(role);
    }

    public Role fetchRoleById(Long id) throws Exception {
        Optional<Role> role = this.roleRepository.findById(id);
        if (role.isPresent()) {
            return role.get();
        } else {
            throw new AppException(ErrorCode.ROLE_NOT_FOUND);
        }   
    }

    public Role update(Role role) throws Exception {
        Role currentRole = this.fetchRoleById(role.getId());

        if (role.getPermissions() != null) {
            List<Long> reqPermissions = role.getPermissions()
                    .stream().map(
                            Permission::getId)
                    .collect(Collectors.toList());
            List<Permission> dbPermissions = this.permissionRepository.findByIdIn(reqPermissions);
            currentRole.setPermissions(dbPermissions);
        }

        currentRole.setName(role.getName());
        currentRole.setActive(role.isActive());
        currentRole.setDescription(role.getDescription());

        return this.roleRepository.save(currentRole);
    }

    public void delete(Long id) throws Exception {
        Role role = this.fetchRoleById(id);

        this.roleRepository.delete(role);
    }

    public List<Role> fetchAll() {
        return roleRepository.findAll();
    }

}