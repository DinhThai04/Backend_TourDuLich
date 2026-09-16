package com.example.auth_service.controller;


import com.example.auth_service.config.CustomJwtDecoder;
import com.example.auth_service.config.SecurityConfig;
import com.example.auth_service.dto.request.AuthenticationRequest;
import com.example.auth_service.dto.response.AuthenticationResponse;
import com.example.auth_service.dto.response.UserResponse;
import com.example.auth_service.exception.AppException;
import com.example.auth_service.exception.ErrorCode;
import com.example.auth_service.exception.GlobalExceptionHandler;
import com.example.auth_service.service.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Date;

import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.CoreMatchers.is;

@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
public class AuthControllerTest {

    /**
     * Đây là công cụ chính để mô phỏng các lời gọi HTTP (GET, POST,...)
     * đến Controller của bạn.
     */
    @Autowired
    private MockMvc mockMvc;

    /**
     * Đây là tiện ích Spring tự động cấu hình, giúp bạn
     * chuyển đổi một Java Object (ví dụ: AuthenticationRequest)
     * thành một chuỗi JSON để gửi đi trong request body.
     */
    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Vì @WebMvcTest không tải bean @Service, chúng ta phải
     * cung cấp một phiên bản "giả" (mock) của nó.
     * Controller sẽ tiêm (autowire) mock này thay vì service thật.
     */
    @MockBean
    private AuthService authService;

    @MockBean
    private CustomJwtDecoder customJwtDecoder;

    @Test
    void login_whenValidRequest_thenReturnsResponse() throws Exception {
        // Tạo một đối tượng request mà client sẽ gửi lên
        AuthenticationRequest authRequest = new AuthenticationRequest();
        authRequest.setEmail("email1@gmail.com");
        authRequest.setPassword("123");

        // Tạo 1 UserResponse
        UserResponse user = new UserResponse();
        user.setId("#1");
        user.setUsername("usr1");
        user.setAvatar("avatar1");
        user.setAddress("address1");
        user.setPhone("phone1");
        user.setEmail("email1@gmail.com");

        // Tạo một đối tượng response "giả" mà ta mong đợi Service trả về
        AuthenticationResponse authResponse = AuthenticationResponse.builder()
                .token("mock-jwt-token-123")
                .expiryTime(new Date()) // Khởi tạo một Date bất kỳ
                .user(user)
                .build();

        // "Dạy" cho Mock Service:
        // "Khi phương thức 'authenticated' được gọi với BẤT KỲ (any)
        // đối tượng AuthenticationRequest nào, THÌ (then)
        // hãy trả về (Return) đối tượng 'serviceResponse' ở trên."
        when(authService.authenticated(any(AuthenticationRequest.class)))
                .thenReturn(authResponse);


        // Thực hiện gọi API POST /users/login
        mockMvc.perform(post("/users/login")
                        .contentType(MediaType.APPLICATION_JSON) // Đặt Header Content-Type
                        .content(objectMapper.writeValueAsString(authRequest))) // Chuyển object -> JSON string

                // Mong đợi HTTP Status Code là 200 (OK)
                .andExpect(status().isOk())

                // Dùng JSONPath để "đào" vào nội dung JSON trả về
                // Ký tự $ đại diện cho root (toàn bộ response)
                .andExpect(jsonPath("$.code", is(1000))) // Kiểm tra code 1000 mặc định
                .andExpect(jsonPath("$.result.token", is("mock-jwt-token-123"))) // Kiểm tra token
                .andExpect(jsonPath("$.result.expiryTime", is(notNullValue()))) // Kiểm tra expiryTime tồn tại
                .andExpect(jsonPath("$.result.user.email", is("email1@gmail.com"))); // Kiểm tra user lồng
    }


    @Test
    void login_whenInvalidCredentials_thenReturnsAppException() throws Exception {
        // ARRANGE

        // 1. Chuẩn bị request
        AuthenticationRequest loginRequest = AuthenticationRequest.builder()
                .email("email1@gmail.com")
                .password("wrongpassword")
                .build();

        // 2. Chuẩn bị mã lỗi (ví dụ: lỗi unauthorized từ enum)
        ErrorCode unauthorizedError = ErrorCode.UNAUTHENTICATED;

        // 3. "Dạy" service ném ra AppException
        when(authService.authenticated(any(AuthenticationRequest.class)))
                // Dùng chính xác constructor: new AppException(ErrorCode)
                .thenThrow(new AppException(unauthorizedError));

        // ACT & ASSERT
        mockMvc.perform(post("/users/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))

                // Mong đợi status (ví dụ: 401)
                .andExpect(status().is(unauthorizedError.getStatusCode().value()))
                // Mong đợi code lỗi (ví dụ: 1006)
                .andExpect(jsonPath("$.code", is(unauthorizedError.getCode())))
                // Mong đợi message lỗi
                .andExpect(jsonPath("$.message", is(unauthorizedError.getMessage())));
    }


    @Test
    void login_whenEmailIsBlank_thenReturnsBadRequest() throws Exception {
        // ARRANGE
        // 1. Tạo một request không hợp lệ (password để trống)
        AuthenticationRequest invalidRequest = new AuthenticationRequest();
        invalidRequest.setEmail("");// Vi phạm validation @NotBlank, @Email
        invalidRequest.setPassword("password123");

        // 2. Chuẩn bị mã lỗi (ví dụ: lỗi EMAIL_IS_REQUIRED)
        ErrorCode mailError  = ErrorCode.EMAIL_IS_REQUIRED;

        // ACT & ASSERT
        mockMvc.perform(post("/users/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))

                // 1. Mong đợi status 400 Bad Request
                // (Vì @Valid sẽ thất bại và GlobalExceptionHandler sẽ xử lý)
                .andExpect(status().is(mailError.getStatusCode().value()))

                // 2. Kiểm tra nội dung lỗi
                // Giả sử GlobalExceptionHandler của bạn trả về một ErrorCode
                // cụ thể cho validation: 1009 - EMAIL_IS_REQUIRED
                // MethodArgumentNotValidException
                .andExpect(jsonPath("$.code", is(1009)))
                .andExpect(jsonPath("$.message", is("Email is required")));

        // 3. Quan trọng: Xác nhận rằng service KHÔNG BAO GIỜ được gọi
        // vì request đã bị chặn ở lớp validation
        verify(authService, never()).authenticated(any(AuthenticationRequest.class));
    }
}
