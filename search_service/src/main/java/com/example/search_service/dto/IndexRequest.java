package com.example.search_service.dto;

import lombok.*;
import lombok.experimental.FieldDefaults;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class IndexRequest {
    private String source;
    private int entityId;
    private String title;
    private String description;
    private String keywords;
}
