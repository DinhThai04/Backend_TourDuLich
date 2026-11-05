package com.example.auth_service.dto.request;

import jakarta.validation.constraints.*;
import lombok.Data;
import java.util.List;

@Data
public class PermissionRequest {
    @Positive private Long id;
    @Pattern(regexp = "[A-Za-z][A-Za-z0-9_]{1,99}", message = "Mã quyền chỉ gồm chữ, số và dấu gạch dưới")
    private String code;
    @NotBlank @Size(max = 100) private String name;
    @NotBlank @Size(max = 255) @Pattern(regexp = "^/[^\\s]*$") private String apiPath;
    @NotBlank @Pattern(regexp = "GET|POST|PUT|PATCH|DELETE") private String method;
    @NotBlank @Size(max = 100) private String module;
    @Size(max = 100) private List<@NotNull @Positive Long> roleIds;
}
