package com.example.auth_service.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;
import com.example.auth_service.entity.Role;
import java.util.Optional;

@Repository
public interface RoleRepository extends
        JpaRepository<Role, Long>,
        JpaSpecificationExecutor<Role> {
    boolean existsByName(String name);

    Optional<Role> findByNameIgnoreCase(String name);

    Role findByName(String name);

    // Roles are few and shared by every account, so loading their permissions once per request
    // keeps the paged user list from resolving the lazy graph row by row.
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = "permissions")
    @org.springframework.data.jpa.repository.Query("select r from Role r")
    java.util.List<Role> findAllWithPermissions();
}