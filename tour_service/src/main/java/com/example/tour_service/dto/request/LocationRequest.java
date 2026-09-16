package com.example.tour_service.dto.request;

import com.example.tour_service.enums.LocationType;

import lombok.*;
import lombok.experimental.FieldDefaults;
import org.springframework.web.multipart.MultipartFile;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class LocationRequest {
    String city;
    LocationType type;
    MultipartFile img;

}
