package com.example.auth_service.controller;

import com.example.auth_service.dto.request.UserCreationRequest;
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

    private UserCreationRequest validRequest;
    private UserCreationRequest invalidRequest;
    private UserResponse userResponse;

    @BeforeEach
    void initData() {
        // ✅ Request hợp lệ
        validRequest = UserCreationRequest.builder()
                .username("join")
                .address("daklak")
                .email("muoi@gmail.com")
                .password("123456")
                .phone("0868166353")
                
                .build();

        // ❌ Request không hợp lệ (username rỗng)
        invalidRequest = UserCreationRequest.builder()
                .username("") 
                .address("daklak")
                .email("muoi@gmail.com")
                .password("123456")
                .phone("0868166353")
                .build();

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
        Mockito.when(userService.createUser(Mockito.any())).thenReturn(userResponse);

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

        Mockito.when(userService.getAllUser()).thenReturn(mockUsers);;

        mockMvc.perform(MockMvcRequestBuilders
                .get("/users/list")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.result[0].username").value("join"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.result[1].username").value("hoa123"));
    }

}
