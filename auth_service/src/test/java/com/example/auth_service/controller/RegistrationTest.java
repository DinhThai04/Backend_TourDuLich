package com.example.auth_service.controller;

import com.example.auth_service.dto.request.UserCreationRequest;
import com.example.auth_service.dto.response.UserResponse;
import com.example.auth_service.entity.*;
import com.example.auth_service.exception.GlobalExceptionHandler;
import com.example.auth_service.mapper.UserMapper;
import com.example.auth_service.repository.*;
import com.example.auth_service.repository.httpclient.FileServiceClient;
import com.example.auth_service.service.UserService;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RegistrationTest {
    UserRepository users;
    RoleRepository roles;
    UserService service;
    Role customer;
    MockMvc mvc;
    LocalValidatorFactoryBean validator;
    final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);
    final String valid = """
        {"username":"  traveler01  ","email":"  traveler@example.com  ",
         "phone":"0900000000","address":"Ha Noi","password":"Password123!"}
        """;

    @BeforeEach void setup() {
        users = mock(UserRepository.class); roles = mock(RoleRepository.class);
        var mapper = mock(UserMapper.class);
        service = new UserService(mock(com.example.auth_service.service.AccountLifecycleService.class), users, mapper, encoder, mock(FileServiceClient.class), roles, new com.example.auth_service.config.AccountAccess(users), mock(com.example.auth_service.service.AdminContinuity.class), mock(com.example.auth_service.service.AvatarProfileWriter.class), mock(com.example.auth_service.service.SessionEpochService.class));
        ReflectionTestUtils.setField(service, "customerRoleName", "Khách Hàng");
        customer = new Role(); customer.setId(99L); customer.setName("Khách Hàng"); customer.setActive(true);
        customer.setPermissions(List.of());
        when(roles.findByName("Khách Hàng")).thenReturn(customer);
        when(roles.findById(99L)).thenReturn(Optional.of(customer));
        when(mapper.toUser(any())).thenAnswer(inv -> {
            UserCreationRequest r = inv.getArgument(0);
            return User.builder().username(r.getUsername()).email(r.getEmail()).phone(r.getPhone())
                .address(r.getAddress()).build();
        });
        when(users.save(any())).thenAnswer(inv -> { User u = inv.getArgument(0); u.setId(123L); return u; });
        when(mapper.toUserResponse(any())).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            return UserResponse.builder().id(u.getId().toString()).username(u.getUsername())
                .email(u.getEmail()).phone(u.getPhone()).address(u.getAddress()).build();
        });
        validator = new LocalValidatorFactoryBean(); validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new UserController(service))
            .setValidator(validator).setControllerAdvice(new GlobalExceptionHandler()).build();
    }
    @AfterEach void closeValidator() { validator.close(); }

    @Test void validRegistrationUsesExistingCustomerRoleAndHashesPassword() throws Exception {
        mvc.perform(post("/users/register").contentType("application/json").content(valid))
            .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1000))
            .andExpect(jsonPath("$.result.username").value("traveler01"))
            .andExpect(jsonPath("$.result.phone").value("0900000000"))
            .andExpect(jsonPath("$.result.address").value("Ha Noi"));
        var captor = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(users).save(captor.capture());
        assertEquals(99L, captor.getValue().getRole().getId());
        assertTrue(encoder.matches("Password123!", captor.getValue().getPassword()));
        verify(roles, never()).save(any());
    }
    @Test void publicCallerCannotAssignAdminRole() throws Exception {
        String body = valid.replace("\"password\":", "\"roleId\":1,\"password\":");
        mvc.perform(post("/users/register").contentType("application/json").content(body)).andExpect(status().isOk());
        verify(roles, never()).findById(1L);
        var captor = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(users).save(captor.capture()); assertEquals(99L, captor.getValue().getRole().getId());
    }
    @Test void missingOrInactiveCustomerRoleFailsClearlyWithoutCreatingRole() throws Exception {
        when(roles.findByName("Khách Hàng")).thenReturn(null);
        mvc.perform(post("/users/register").contentType("application/json").content(valid))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(1023));
        customer.setActive(false); when(roles.findByName("Khách Hàng")).thenReturn(customer);
        mvc.perform(post("/users/register").contentType("application/json").content(valid))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(1023));
        verify(users, never()).save(any()); verify(roles, never()).save(any());
    }
    @Test void configuredRoleNameCanUseExistingRoleWithAnyId() throws Exception {
        ReflectionTestUtils.setField(service, "customerRoleName", "CUSTOMER");
        customer.setName("CUSTOMER"); when(roles.findByName("CUSTOMER")).thenReturn(customer);
        mvc.perform(post("/users/register").contentType("application/json").content(valid)).andExpect(status().isOk());
        verify(roles,atLeastOnce()).findByName("CUSTOMER");
    }
    @Test void duplicateEmailReportsGenericConflictWithoutRevealingField() throws Exception {
        when(users.existsByEmail("traveler@example.com")).thenReturn(true);
        mvc.perform(post("/users/register").contentType("application/json").content(valid))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1021))
            .andExpect(jsonPath("$.message").value("Email hoặc tên tài khoản đã được sử dụng"));
        verify(users, never()).save(any());
    }
    @Test void duplicateUsernameReportsGenericConflictWithoutRevealingField() throws Exception {
        when(users.existsByUsername("traveler01")).thenReturn(true);
        mvc.perform(post("/users/register").contentType("application/json").content(valid))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1021))
            .andExpect(jsonPath("$.message").value("Email hoặc tên tài khoản đã được sử dụng"));
        verify(users, never()).save(any());
    }
    @Test void invalidFieldsAreRejectedWithTheirValidationMessages() throws Exception {
        for (var example : List.of(
            Map.entry(valid.replace("Ha Noi", "  "), "Địa chỉ không được để trống"),
            Map.entry(valid.replace("Password123!", "123"), "Mật khẩu phải từ 8 đến 72 ký tự"),
            Map.entry(valid.replace("0900000000", "abc"), "Số điện thoại phải có từ 9 đến 11 chữ số"),
            Map.entry(valid.replace("traveler@example.com", "not-an-email"), "Email không hợp lệ"),
            Map.entry(valid.replace("traveler01", "a"), "Tên tài khoản phải từ 3 đến 50 ký tự"))) {
            mvc.perform(post("/users/register").contentType("application/json").content(example.getKey()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(example.getValue()));
        }
        verify(users, never()).save(any());
    }
    @Test void managedCreationUsesActiveDatabaseAdminOrExactPermissions() {
        var admin = User.builder().id(7L).role(customer).build();
        var auth = new UsernamePasswordAuthenticationToken("7", "", List.of());
        when(users.findById(7L)).thenReturn(Optional.of(admin));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
        customer.setName("admin"); assertTrue(service.canCreateManagedUser(auth));
        customer.setName("Renamed role");
        customer.setPermissions(List.of(
            new Permission("USERS_REGISTER", "Tạo người dùng", "/api/v1/auth/users/register", "POST", "USERS"),
            new Permission("USERS_GET_LIST", "Xem người dùng", "/api/v1/auth/users/list", "GET", "USERS")));
        assertTrue(service.canCreateManagedUser(auth));
        assertFalse(service.canCreateManagedUser(null));
    }
    @AfterEach void clearAuthentication() { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    @Test void staffCreatingCustomersCannotAssignAnAdminRole() {
        var role=new Role();role.setId(1L);role.setName("admin");role.setActive(true);
        when(roles.findById(1L)).thenReturn(Optional.of(role));
        var request=UserCreationRequest.builder().username("newadmin").email("newadmin@example.test").phone("0900000000")
            .address("Test").password("Password123!").roleId(1L).build();
        assertThrows(org.springframework.security.access.AccessDeniedException.class,()->service.createUser(request));
        verify(users,never()).save(any());
    }
}
