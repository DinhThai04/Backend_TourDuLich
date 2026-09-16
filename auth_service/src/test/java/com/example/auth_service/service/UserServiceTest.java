package com.example.auth_service.service;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.ThrowableAssert.catchThrowable;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.example.auth_service.repository.UserRepository;

import org.springframework.security.crypto.password.PasswordEncoder;

import com.example.auth_service.dto.request.UserCreationRequest;
import com.example.auth_service.dto.response.UserResponse;
import com.example.auth_service.entity.User;
import com.example.auth_service.exception.AppException;
import com.example.auth_service.exception.ErrorCode;
import com.example.auth_service.mapper.UserMapper;

@ExtendWith(MockitoExtension.class)
public class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private UserMapper userMapper;

    @InjectMocks
    private UserService userService;

    private User user;
    private UserResponse userResponse;
    private UserCreationRequest userCreationRequest;

    @BeforeEach
    void initData() {
        user = User.builder()
                .id(123L)
                .username("join")
                .email("join@gmail.com")
                .address("daklak")
                .phone("0868166353")
                .build();

        userCreationRequest = UserCreationRequest.builder()
                .username("join")
                .email("join@gmail.com")
                .address("daklak")
                .phone("0868166353")
                .password("123456")
                .build();

        userResponse = UserResponse.builder()
                .id("123")
                .username("join")
                .email("join@gmail.com")
                .address("daklak")
                .phone("0868166353")
                .build();
    }

    @Test
    void createUser_validRequest_success() {
        when(userMapper.toUser(userCreationRequest)).thenReturn(user);
        when(passwordEncoder.encode("123456")).thenReturn("encoded");
        when(userRepository.save(user)).thenReturn(user);
        when(userMapper.toUserResponse(user)).thenReturn(userResponse);

        var result = userService.createUser(userCreationRequest);

        assertThat(result.getId()).isEqualTo("123");
        assertThat(result.getUsername()).isEqualTo("join");
    }

    @Test
    void getUserById_userNotFound_throwException() {
        // given
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> userService.getUserById(999L))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.User_name);
        Throwable thrown = catchThrowable(() -> userService.getUserById(999L));
        Assertions.assertThat(thrown.getMessage()).isEqualTo("User not existed");
    }

}
