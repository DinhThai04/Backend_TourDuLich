package com.example.auth_service.service;

import com.example.auth_service.entity.Permission;
import com.example.auth_service.entity.Role;
import com.example.auth_service.repository.PermissionRepository;
import com.example.auth_service.repository.RoleRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest(properties = {"spring.datasource.url=jdbc:h2:mem:role-test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"})
@Import({RoleService.class, AdminContinuity.class, SessionEpochService.class})
class RolePersistenceTest {
    @Autowired RoleService service;
    @Autowired RoleRepository roles;
    @Autowired PermissionRepository permissions;
    @Autowired EntityManager entityManager;

    @Test void roleReadsLoadPermissionsBeforeTheSessionCloses() throws Exception {
        var permission = permissions.save(new Permission("ROLES_READ", "Roles read", "/api/v1/roles", "GET", "AUTH"));
        var role = new Role();
        role.setName("Test role");
        role.setActive(true);
        role.setPermissions(new ArrayList<>(List.of(permission)));
        Long roleId = roles.save(role).getId();
        entityManager.flush();
        entityManager.clear();

        List<Role> all = service.fetchAll();
        Role byId = service.fetchRoleById(roleId);
        // The controller serializes these entities after the transaction has closed.
        entityManager.clear();

        assertTrue(all.stream().anyMatch(item -> item.getPermissions().stream()
                .anyMatch(granted -> "ROLES_READ".equals(granted.getCode()))));
        assertEquals(1, byId.getPermissions().size());
    }
}
