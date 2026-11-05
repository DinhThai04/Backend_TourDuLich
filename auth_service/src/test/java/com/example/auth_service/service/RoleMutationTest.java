package com.example.auth_service.service;

import com.example.auth_service.entity.Permission;
import com.example.auth_service.entity.Role;
import com.example.auth_service.exception.AppException;
import com.example.auth_service.exception.ErrorCode;
import com.example.auth_service.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoleMutationTest {
    @Test void createCannotOverwriteAnExistingRoleBySupplyingItsId() {
        var roles = mock(RoleRepository.class);
        var service = service(roles);
        var request = new Role(); request.setId(1L); request.setName("Replacement");
        var error = assertThrows(ResponseStatusException.class, () -> service.create(request));
        assertEquals(400, error.getStatusCode().value()); verifyNoInteractions(roles);
    }
    @Test void updateRequiresAnExplicitRoleId() {
        var roles = mock(RoleRepository.class);
        var service = service(roles);
        assertThrows(ResponseStatusException.class, () -> service.update(new Role()));
        verifyNoInteractions(roles);
    }
    @Test void createRejectsReservedBuiltInNamesIgnoringCaseAndSpaces() {
        var roles = mock(RoleRepository.class);
        var service = service(roles);
        for (String reserved : new String[]{"ADMIN", " admin ", "Customer", "USER"}) {
            var request = new Role(); request.setName(reserved);
            var error = assertThrows(AppException.class, () -> service.create(request));
            assertEquals(ErrorCode.ROLE_NAME_RESERVED, error.getErrorCode());
        }
        verifyNoInteractions(roles);
    }
    @Test void createRejectsTheConfiguredCustomerRoleName() {
        var roles = mock(RoleRepository.class);
        var service = service(roles);
        ReflectionTestUtils.setField(service, "customerRoleName", "Khách Hàng");
        var request = new Role(); request.setName("  khách hàng ");
        var error = assertThrows(AppException.class, () -> service.create(request));
        assertEquals(ErrorCode.ROLE_NAME_RESERVED, error.getErrorCode());
    }
    @Test void createRejectsADuplicateNameIgnoringCase() {
        var roles = mock(RoleRepository.class);
        var service = service(roles);
        var existing = new Role(); existing.setId(3L); existing.setName("Staff");
        when(roles.findByNameIgnoreCase(anyString())).thenReturn(Optional.of(existing));
        var request = new Role(); request.setName(" STAFF ");
        var error = assertThrows(AppException.class, () -> service.create(request));
        assertEquals(ErrorCode.ROLE_NAME_ALREADY_EXISTS, error.getErrorCode());
        verify(roles, never()).save(any());
    }
    @Test void updateCannotTakeAnotherRolesNameButMayKeepItsOwn() throws Exception {
        var roles = mock(RoleRepository.class);
        var service = service(roles);
        var current = new Role(); current.setId(2L); current.setName("Staff"); current.setActive(true);
        var other = new Role(); other.setId(3L); other.setName("Manager");
        when(roles.findById(2L)).thenReturn(Optional.of(current));
        when(roles.findByNameIgnoreCase("manager")).thenReturn(Optional.of(other));
        var collision = new Role(); collision.setId(2L); collision.setName("manager"); collision.setActive(true);
        var error = assertThrows(AppException.class, () -> service.update(collision));
        assertEquals(ErrorCode.ROLE_NAME_ALREADY_EXISTS, error.getErrorCode());

        when(roles.findByNameIgnoreCase("staff")).thenReturn(Optional.of(current));
        when(roles.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var same = new Role(); same.setId(2L); same.setName(" staff "); same.setActive(true);
        assertEquals("staff", service.update(same).getName());
    }
    @Test void builtInRoleCannotBeRenamed() throws Exception {
        var roles = mock(RoleRepository.class);
        var service = service(roles);
        var admin = new Role(); admin.setId(1L); admin.setName("admin"); admin.setActive(true);
        when(roles.findById(1L)).thenReturn(Optional.of(admin));
        var rename = new Role(); rename.setId(1L); rename.setName("Owner"); rename.setActive(true);
        var error = assertThrows(AppException.class, () -> service.update(rename));
        assertEquals(ErrorCode.ROLE_NAME_RESERVED, error.getErrorCode());
    }
    @Test void changingPermissionsRevokesSessionsOfEveryRoleMember() throws Exception {
        var roles = mock(RoleRepository.class);
        var users = mock(UserRepository.class);
        var permissions = mock(PermissionRepository.class);
        var epochs = mock(SessionEpochService.class);
        var service = new RoleService(roles, permissions, mock(AdminContinuity.class), users, epochs);
        var current = new Role(); current.setId(2L); current.setName("Staff"); current.setActive(true);
        when(roles.findById(2L)).thenReturn(Optional.of(current));
        when(roles.findByNameIgnoreCase("Staff")).thenReturn(Optional.of(current));
        when(roles.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var permission = new Permission("TOURS_UPDATE", "Cập nhật tour", "/tour", "PUT", "TOUR");
        permission.setId(7L);
        when(permissions.findByIdIn(List.of(7L))).thenReturn(List.of(permission));
        when(users.findIdsByRoleId(2L)).thenReturn(List.of(5L, 6L));
        var update = new Role(); update.setId(2L); update.setName("Staff"); update.setActive(true);
        update.setPermissions(new ArrayList<>(List.of(permission)));
        service.update(update);
        verify(epochs).revokeAll(eq(List.of(5L, 6L)), contains("Staff"));
    }
    @Test void editingOnlyTheDescriptionKeepsSessions() throws Exception {
        var roles = mock(RoleRepository.class);
        var epochs = mock(SessionEpochService.class);
        var service = new RoleService(roles, mock(PermissionRepository.class), mock(AdminContinuity.class),
                mock(UserRepository.class), epochs);
        var current = new Role(); current.setId(2L); current.setName("Staff"); current.setActive(true);
        when(roles.findById(2L)).thenReturn(Optional.of(current));
        when(roles.findByNameIgnoreCase("Staff")).thenReturn(Optional.of(current));
        when(roles.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var update = new Role(); update.setId(2L); update.setName("Staff"); update.setActive(true);
        update.setDescription("Mô tả mới");
        service.update(update);
        verify(epochs, never()).revokeAll(any(), anyString());
    }
    private static RoleService service(RoleRepository roles) {
        return new RoleService(roles, mock(PermissionRepository.class), mock(AdminContinuity.class),
                mock(UserRepository.class), mock(SessionEpochService.class));
    }
}
