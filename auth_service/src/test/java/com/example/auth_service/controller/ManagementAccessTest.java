package com.example.auth_service.controller;

import com.example.auth_service.config.*;
import com.example.auth_service.entity.*;
import com.example.auth_service.repository.UserRepository;
import com.example.auth_service.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({UserController.class, RoleController.class, PermissionController.class, ReviewController.class})
@Import({SecurityConfig.class, AccountAccess.class})
class ManagementAccessTest {
    @Autowired MockMvc mvc;
    @MockBean CustomJwtDecoder decoder;
    @MockBean UserRepository users;
    @MockBean UserService userService;
    @MockBean RoleService roleService;
    @MockBean PermissionService permissionService;
    @MockBean ReviewService reviewService;
    @BeforeEach void setup() {
        for (long id : new long[]{1, 2, 3, 4, 5}) {
            var role = new Role(); role.setActive(true); role.setName(id == 3 ? "admin" : "staff");
            role.setPermissions(id == 1 ? List.of(new Permission("UI_ENTRY", "Lối vào quản trị", "/ui/admin", "GET", "UI"))
                    : id == 4 ? List.of(new Permission("USERS_GET_LIST", "Xem người dùng", "/api/v1/auth/users/list", "GET", "USERS"))
                    : id == 5 ? List.of(new Permission("BOOKINGS_GET_ALL", "Xem đặt chỗ", "/api/v1/booking/book/all", "GET", "BOOKINGS")) : List.of());
            when(users.findById(id)).thenReturn(Optional.of(User.builder().id(id).role(role).build()));
        }
    }
    @Test void customerCannotReadOtherUserOrManagementLists() throws Exception {
        for (String path : List.of("/users/1", "/users/list", "/roles", "/permissions/list"))
            mvc.perform(get(path).with(jwt().jwt(j -> j.subject("2")))).andExpect(status().isForbidden());
        verifyNoInteractions(userService, roleService, permissionService);
    }
    @Test void ownProfileAndAdminUserListAreAllowed() throws Exception {
        mvc.perform(get("/users/2").with(jwt().jwt(j -> j.subject("2")))).andExpect(status().isOk());
        mvc.perform(get("/users/list").with(jwt().jwt(j -> j.subject("3")))).andExpect(status().isOk());
        verify(userService).getUserById(2L); verify(userService).getAllUser(null, null);
    }
    @Test void customerCannotChangeOtherProfileOrPermissions() throws Exception {
        mvc.perform(post("/users/UpdateUser/1").param("username", "hacked")
                .with(jwt().jwt(j -> j.subject("2")))).andExpect(status().isForbidden());
        mvc.perform(post("/permissions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"TEST\",\"apiPath\":\"/test\",\"method\":\"GET\",\"module\":\"TEST\",\"roleIds\":[2]}")
                .with(jwt().jwt(j -> j.subject("2")))).andExpect(status().isForbidden());
        verifyNoInteractions(userService, permissionService);
    }
    @Test void invalidReviewNeverReachesService() throws Exception {
        mvc.perform(post("/reviews").with(jwt().jwt(j -> j.subject("2")))
                .contentType(MediaType.APPLICATION_JSON).content("{\"tourId\":1,\"content\":\"ok\",\"rating\":6}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(reviewService);
    }
    @Test void uiEntryCannotDeleteUsersEvenWithLegacyAdminAuthority() throws Exception {
        mvc.perform(delete("/users/99").contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"test\"}").with(jwt().jwt(j -> j.subject("1"))
                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ACCESS_ADMIN_LEGACY"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(userService);
    }
    @Test void exactReadPermissionDoesNotGrantDelete() throws Exception {
        mvc.perform(get("/users/list").with(jwt().jwt(j -> j.subject("4")))).andExpect(status().isOk());
        mvc.perform(delete("/users/99").contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"test\"}").with(jwt().jwt(j -> j.subject("4")))).andExpect(status().isForbidden());
        verify(userService).getAllUser(null, null); verify(userService,never()).deleteUser(anyLong(),anyString());
    }
    @Test void databaseAdminRoleRetainsFullAccess() throws Exception {
        mvc.perform(delete("/users/99").param("reason", "test").with(jwt().jwt(j -> j.subject("3")))).andExpect(status().isOk());
        verify(userService).deleteUser(99L,"test");
    }
    @Test void namesEndpointExposesLabelsOnlyAndRejectsCustomers() throws Exception {
        when(userService.displayNames(List.of(9L))).thenReturn(Map.of(9L,"Booking owner"));
        mvc.perform(get("/users/display-names").param("ids","9").with(jwt().jwt(j->j.subject("2")))).andExpect(status().isForbidden());
        mvc.perform(get("/users/display-names").param("ids","9").with(jwt().jwt(j->j.subject("5"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.9").value("Booking owner"));
        mvc.perform(get("/users/list").with(jwt().jwt(j->j.subject("5")))).andExpect(status().isForbidden());
    }
    @Test void permissionEditorCannotRewriteCapabilitiesWithoutRoleManagement() throws Exception {
        var role=new Role();role.setName("staff");role.setActive(true);role.setPermissions(List.of(
                new Permission("PERMISSIONS_UPDATE","Sửa quyền","/api/v1/auth/permissions","PUT","AUTH"),
                new Permission("PERMISSIONS_DELETE","Xóa quyền","/api/v1/auth/permissions/{id}","DELETE","AUTH")));
        when(users.findById(8L)).thenReturn(Optional.of(User.builder().id(8L).role(role).build()));
        mvc.perform(put("/permissions").with(jwt().jwt(j->j.subject("8"))).contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":11,\"name\":\"grant\",\"apiPath\":\"/api/v1/auth/users/{id}\",\"method\":\"DELETE\",\"module\":\"AUTH\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/permissions/11").with(jwt().jwt(j->j.subject("8")))).andExpect(status().isForbidden());
        verifyNoInteractions(permissionService);
    }
}
