package com.example.booking_service.client;
import com.example.booking_service.config.FeignAuthConfig;
import com.example.booking_service.dto.response.ApiResponse;
import com.example.booking_service.dto.response.UserResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(
        name = "user-service",
        url = "http://localhost:8888/api/v1/auth",
        configuration = FeignAuthConfig.class
)
public interface UserClient {
    @GetMapping("/users/{id}")
    ApiResponse<UserResponse> getUserById(@PathVariable("id") Long id);
}