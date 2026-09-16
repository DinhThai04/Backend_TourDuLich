package com.example.auth_service.dto.response;


import java.util.List;

import com.example.auth_service.entity.Permission;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class RoleResponse {
    private long id;
    private String name;
    private List<Permission> permissions;
}