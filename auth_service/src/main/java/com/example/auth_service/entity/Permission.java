package com.example.auth_service.entity;

import jakarta.persistence.*;
import lombok.*;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonIgnore;

@Entity
@Table(name = "permissions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Permission {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Stable authorization key; it is what the JWT carries and code checks against. */
    @NotBlank(message = "Code is required")
    @Column(nullable = false, unique = true, length = 100)
    private String code;

    /** Human-readable label; editable without changing any granted access. */
    @NotBlank(message = "Name is required")
    private String name;

    @NotBlank(message = "API Path is required")
    private String apiPath;

    @NotBlank(message = "Method is required")
    private String method;

    @NotBlank(message = "Module is required")
    private String module;

    @ManyToMany(fetch = FetchType.LAZY, mappedBy = "permissions")
    @JsonIgnore
    private List<Role> roles;

    public Permission(String name, String apiPath, String method, String module) {
        this(name, name, apiPath, method, module);
    }

    public Permission(String code, String name, String apiPath, String method, String module) {
        this.code = code;
        this.name = name;
        this.apiPath = apiPath;
        this.method = method;
        this.module = module;
    }
}