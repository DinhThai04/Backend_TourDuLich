package com.example.auth_service.controller;

import com.example.auth_service.dto.request.RegisterRequest;
import com.example.auth_service.dto.response.UserResponse;
import com.example.auth_service.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;



@WebMvcTest(UserController.class)
@AutoConfigureMockMvc(addFilters = false) 
public class UserControllerTest {

    @Autowired
    private MockMvc mockMvc; 

    @MockBean
    private UserService userService;


    @Autowired
    private ObjectMapper objectMapper;

    private RegisterRequest validRequest;
    private RegisterRequest invalidRequest;
    private UserResponse userResponse;

    @BeforeEach
    void initData() {
        // ✅ Request hợp lệ
        validRequest = new RegisterRequest();
        validRequest.setUsername("join");
        validRequest.setAddress("daklak");
        validRequest.setEmail("muoi@gmail.com");
        validRequest.setPassword("Admin@123456");
        validRequest.setPhone("0868166353");

        // ❌ Request không hợp lệ (username rỗng)
        invalidRequest = new RegisterRequest();
        invalidRequest.setUsername("");
        invalidRequest.setAddress("daklak");
        invalidRequest.setEmail("muoi@gmail.com");
        invalidRequest.setPassword("Admin@123456");
        invalidRequest.setPhone("0868166353");

        // ✅ Dữ liệu trả về từ service
        userResponse = UserResponse.builder()
                .id("123")
                .username("join")
                .address("daklak")
                .email("muoi@gmail.com")
                .phone("0868166353")
                .build();
    }

    @Test
    void createUser_validRequest_success() throws Exception {
        Mockito.when(userService.registerCustomer(Mockito.any())).thenReturn(userResponse);

        mockMvc.perform(MockMvcRequestBuilders
                .post("/users/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validRequest)))
                .andExpect(MockMvcResultMatchers.status().isOk()) 
                .andExpect(MockMvcResultMatchers.jsonPath("$.result.username").value("join"));
    }

    @Test
    void createUser_invalidRequest_shouldFailValidation() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders
                .post("/users/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(MockMvcResultMatchers.status().isBadRequest());

    }

    @Test
    void listUser_success() throws Exception {
        List<UserResponse> mockUsers = Arrays.asList(
                userResponse,
                UserResponse.builder()
                        .id("456")
                        .username("hoa")
                        .email("hoa@gmail.com")
                        .address("hanoi")
                        .phone("0123456789")
                        .build());

        Mockito.when(userService.getAllUser(null, null)).thenReturn(mockUsers);

        mockMvc.perform(MockMvcRequestBuilders
                .get("/users/list")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.result[0].username").value("join"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.result[1].username").value("hoa"));
    }

}
