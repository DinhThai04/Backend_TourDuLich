package com.example.api_gateway.service;


import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.stereotype.Service;

import com.example.api_gateway.dto.request.IntrospectRequest;
import com.example.api_gateway.dto.response.ApiResponse;
import com.example.api_gateway.dto.response.IntrospectResponse;
import com.example.api_gateway.repository.IdentityClient;

import reactor.core.publisher.Mono;

@Service

@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class IdentityService {
    private final IdentityClient identityClient;

    public IdentityService(IdentityClient identityClient) {
        this.identityClient = identityClient;
    }

    public Mono<ApiResponse<IntrospectResponse>> introspect(String token) {
        return identityClient.introspect(IntrospectRequest.builder()
                .token(token)
                .build());
    }
}
