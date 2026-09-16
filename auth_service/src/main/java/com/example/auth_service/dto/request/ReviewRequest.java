package com.example.auth_service.dto.request;

import lombok.Data;

@Data
public class ReviewRequest {
    private Long tourId;
    private Long userId;
    private String content;
    private Integer rating;
    private String username;
    private String avatar;
}
