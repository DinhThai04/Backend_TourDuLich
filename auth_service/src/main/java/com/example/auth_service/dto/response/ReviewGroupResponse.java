package com.example.auth_service.dto.response;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ReviewGroupResponse {
    private Long tourId;
    private String tourName;
    private List<ReviewResponse> reviews;
}
