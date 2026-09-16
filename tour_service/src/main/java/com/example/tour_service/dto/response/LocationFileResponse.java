package com.example.tour_service.dto.response;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LocationFileResponse {
    private Integer locationId;
    private String url;
}
