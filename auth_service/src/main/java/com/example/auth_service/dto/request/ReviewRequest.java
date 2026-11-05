package com.example.auth_service.dto.request;

import lombok.Data;
import jakarta.validation.constraints.*;

@Data
public class ReviewRequest {
    @NotNull @Positive
    private Long tourId;
    @NotBlank @Size(max = 1000)
    private String content;
    @NotNull @Min(1) @Max(5)
    private Integer rating;
}
