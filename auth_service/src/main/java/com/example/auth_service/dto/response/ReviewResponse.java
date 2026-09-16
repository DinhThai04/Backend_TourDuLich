package com.example.auth_service.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ReviewResponse {
  private Long id;
  private Long tourId;
  private UserResponse user;
  private String content;
  private Integer rating;
  private String createdAt;
  private String username;
  private String avatar;
}
