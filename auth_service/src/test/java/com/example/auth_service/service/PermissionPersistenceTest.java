package com.example.auth_service.service;

import com.example.auth_service.dto.request.PermissionRequest;
import com.example.auth_service.entity.Role;
import com.example.auth_service.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import jakarta.persistence.EntityManager;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(properties = {"spring.datasource.url=jdbc:h2:mem:permission-test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"})
@Import({PermissionService.class, SessionEpochService.class})
class PermissionPersistenceTest {
    @Autowired PermissionService service;
    @Autowired PermissionRepository permissions;
    @Autowired RoleRepository roles;
    @Autowired UserRepository users;
    @Autowired UserAccountEventRepository events;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired EntityManager entityManager;
    private PermissionRequest request() {
        var r = new PermissionRequest(); r.setCode("TEST_READ"); r.setName("TEST_READ"); r.setApiPath("/api/v1/test");
        r.setMethod("GET"); r.setModule("TEST"); return r;
    }
    @Test void createUpdateAssignmentAndDeleteSurvivePersistenceReload() {
        var role = new Role(); role.setName("Test role"); role.setActive(true); role = roles.save(role);
        Long roleId = role.getId();
        var request = request(); request.setRoleIds(List.of(roleId));
        Long id = service.create(request).id();
        entityManager.flush(); entityManager.clear();
        assertEquals(1, roles.findById(roleId).orElseThrow().getPermissions().size());
        assertEquals(roleId, service.fetchPermissionById(id).roles().getFirst().id());
        // Saving an unchanged API/method/name used to conflict with itself.
        request.setId(id); service.update(request);
        request.setRoleIds(List.of()); service.update(request);
        entityManager.flush(); entityManager.clear();
        assertTrue(roles.findById(roleId).orElseThrow().getPermissions().isEmpty());
        request.setRoleIds(List.of(roleId)); service.update(request);
        service.delete(id); entityManager.flush(); entityManager.clear();
        assertFalse(permissions.existsById(id));
        assertTrue(roles.findById(roleId).orElseThrow().getPermissions().isEmpty());
    }
    @Test void duplicatesAndUnknownRoleAssignmentsAreRejected() {
        service.create(request());
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> service.create(request()));
        var unknown = request(); unknown.setCode("OTHER"); unknown.setName("OTHER"); unknown.setApiPath("/api/v1/other"); unknown.setRoleIds(List.of(9999L));
        assertThrows(com.example.auth_service.exception.AppException.class, () -> service.create(unknown));
    }
    @Test void revokingAPermissionFromItsRoleLogsOutThatRolesMembers() {
        var role = new Role(); role.setName("Revoked role"); role.setActive(true); role = roles.save(role);
        Long roleId = role.getId();
        var member = users.save(com.example.auth_service.entity.User.builder().username("revoked-member").password("x").email("revoked-member@example.com").role(role).build());
        var colleague = users.save(com.example.auth_service.entity.User.builder().username("kept-member").password("x").email("kept-member@example.com").role(role).build());
        var grant = request(); grant.setCode("REVOKED_READ"); grant.setName("REVOKED_READ"); grant.setApiPath("/api/v1/revoked"); grant.setRoleIds(List.of(roleId));
        Long permissionId = service.create(grant).id();
        entityManager.flush(); entityManager.clear();
        assertEquals(0L, events.sessionEpoch(member.getId()));

        var revoke = request(); revoke.setId(permissionId); revoke.setCode("REVOKED_READ"); revoke.setName("REVOKED_READ"); revoke.setApiPath("/api/v1/revoked");
        revoke.setRoleIds(List.of());
        // The admin on the edit request is the actor of the audit event, not the affected member.
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("77", null, List.of()));
        try {
            service.update(revoke);
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
        entityManager.flush(); entityManager.clear();

        // The epoch is the newest audit event id of that account, so both members now fail the
        // per-token re-check in every service; each gets exactly one revocation record.
        assertTrue(events.sessionEpoch(member.getId()) > 0, "Thành viên bị mất quyền phải bị thu hồi phiên");
        assertTrue(events.sessionEpoch(colleague.getId()) > 0);
        assertEquals(1, events.findByTargetUserIdOrderByIdDesc(colleague.getId(), org.springframework.data.domain.PageRequest.of(0, 5)).size());
        var audit = events.findByTargetUserIdOrderByIdDesc(member.getId(), org.springframework.data.domain.PageRequest.of(0, 1)).getFirst();
        assertEquals("AUTHORIZATION_CHANGE", audit.getAction());
        assertEquals(77L, audit.getActorId());
    }

    /**
     * The revocation must not outlive the authorization change it belongs to: a later statement
     * failing in the same transaction has to roll both back, otherwise accounts would be logged
     * out while their permissions are unchanged.
     */
    @Test void aRolledBackAuthorizationEditLeavesNoRevocation() {
        var role = new Role(); role.setName("Rolled back role"); role.setActive(true); role = roles.save(role);
        var member = users.save(com.example.auth_service.entity.User.builder().username("rolled-back").password("x").email("rolled-back@example.com").role(role).build());
        var grant = request(); grant.setCode("ROLLED_BACK"); grant.setName("ROLLED_BACK"); grant.setApiPath("/api/v1/rolled-back"); grant.setRoleIds(List.of(role.getId()));
        Long permissionId = service.create(grant).id();
        // Publish the setup first: an uncommitted row is invisible to the transaction being rolled back.
        entityManager.flush();
        org.springframework.test.context.transaction.TestTransaction.flagForCommit();
        org.springframework.test.context.transaction.TestTransaction.end();

        try {
            var edit = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
            assertThrows(IllegalStateException.class, () -> edit.executeWithoutResult(ignored -> {
                var revoke = request(); revoke.setId(permissionId); revoke.setCode("ROLLED_BACK"); revoke.setName("ROLLED_BACK");
                revoke.setApiPath("/api/v1/rolled-back"); revoke.setRoleIds(List.of());
                service.update(revoke);
                throw new IllegalStateException("bước sau trong cùng giao dịch thất bại");
            }));
            assertEquals(0L, events.sessionEpoch(member.getId()), "giao dịch rollback không được để lại bản ghi thu hồi");
            assertEquals(role.getId(), service.fetchPermissionById(permissionId).roles().getFirst().id());
        } finally {
            org.springframework.test.context.transaction.TestTransaction.start();
        }
    }
}
