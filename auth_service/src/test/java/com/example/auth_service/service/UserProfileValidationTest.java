package com.example.auth_service.service;

import com.example.auth_service.dto.request.UserUpdate;
import com.example.auth_service.exception.AppException;
import com.example.auth_service.exception.ErrorCode;
import com.example.auth_service.repository.*;
import com.example.auth_service.mapper.UserMapper;
import com.example.auth_service.repository.httpclient.FileServiceClient;
import com.example.auth_service.config.AccountAccess;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserProfileValidationTest {
    UserRepository users=mock(UserRepository.class);
    AdminContinuity guard=mock(AdminContinuity.class);
    UserService service=new UserService(mock(AccountLifecycleService.class),users,mock(UserMapper.class),mock(PasswordEncoder.class),mock(FileServiceClient.class),mock(RoleRepository.class),mock(AccountAccess.class),guard,mock(AvatarProfileWriter.class),mock(SessionEpochService.class));
    @Test void invalidFieldsCannotReachLocksOrRepository() {
        for(String phone:new String[]{"abc","090123","090123456789","","0901234567\n"}) {
            var request=new UserUpdate();request.setPhone(phone);reject(request);
        }
        for(String name:new String[]{" ","ab","x".repeat(51),"valid\nname","valid\u200bname"}) {
            var request=new UserUpdate();request.setUsername(name);reject(request);
        }
        for(String address:new String[]{""," ","x".repeat(256),"address\r\nheader"}) {
            var request=new UserUpdate();request.setAddress(address);reject(request);
        }
        var request=new UserUpdate();request.setRoleId(0L);reject(request);reject(null);
        verifyNoInteractions(users,guard);
    }
    void reject(UserUpdate request){assertEquals(400,assertThrows(ResponseStatusException.class,()->service.updateUser(2L,request)).getStatusCode().value());}
    @Test void nullFieldsRemainOptionalAndValidBoundariesWork() {
        var request=new UserUpdate();assertDoesNotThrow(()->UserProfilePolicy.validate(request));
        request.setUsername("x".repeat(50));request.setAddress("x".repeat(255));request.setPhone("09012345678");request.setRoleId(1L);
        assertDoesNotThrow(()->UserProfilePolicy.validate(request));
    }
    @Test void creationSharesTheUpdateTextRulesAndRunsBeforeTheDatabase() {
        var clean=com.example.auth_service.dto.request.UserCreationRequest.builder()
                .username("customer").address("Ha Noi").build();
        assertDoesNotThrow(()->UserProfilePolicy.validateCreation(clean));
        for(String name:new String[]{"ab","x".repeat(51),"valid\nname","valid\u200bname"}) {
            var bad=com.example.auth_service.dto.request.UserCreationRequest.builder()
                    .username(name).address("Ha Noi").password("password1").build();
            assertEquals(400,assertThrows(ResponseStatusException.class,()->service.createUser(bad)).getStatusCode().value());
        }
        var badAddress=com.example.auth_service.dto.request.UserCreationRequest.builder()
                .username("customer").address("a\u0000b").password("password1").build();
        assertEquals(400,assertThrows(ResponseStatusException.class,()->service.createUser(badAddress)).getStatusCode().value());
        // The policy runs before the uniqueness queries, so a rejected profile never touches SQL.
        verifyNoInteractions(users,guard);
    }
    @Test void dtoValidationAlsoRejectsInvalidHttpFields() {
        try(var factory=jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var request=new UserUpdate();request.setUsername("x".repeat(51));request.setPhone("invalid");request.setAddress("x".repeat(256));request.setRoleId(-1L);
            assertEquals(4,factory.getValidator().validate(request).size());
        }
    }
    @Test void whitespaceCannotBypassMinimumUsernameLength() {
        var request=UserUpdate.builder().username(" a ").build();reject(request);
        request=UserUpdate.builder().username("  customer  ").address("  Ha Noi  ").build();
        UserProfilePolicy.validate(request);assertEquals("customer",request.getUsername());assertEquals("Ha Noi",request.getAddress());
    }
    @Test void usernameClaimedByAConcurrentWriteIsAConflictNotAnInternalError() {
        var target=com.example.auth_service.entity.User.builder().id(2L).username("old-name")
                .status(com.example.auth_service.entity.UserStatus.ACTIVE).build();
        when(users.lockForLifecycle(2L)).thenReturn(java.util.Optional.of(target));
        when(users.findByUsername("taken")).thenReturn(java.util.Optional.empty());
        when(users.existsByUsername("taken")).thenReturn(true);
        when(users.saveAndFlush(any())).thenThrow(new org.springframework.dao.DataIntegrityViolationException("username"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("2","",java.util.List.of()));
        try {
            var error=assertThrows(AppException.class,()->service.updateUser(2L,UserUpdate.builder().username("taken").build()));
            assertEquals(ErrorCode.USER_EXISTED,error.getErrorCode());
        } finally {SecurityContextHolder.clearContext();}
    }
}
