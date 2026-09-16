package com.example.tour_service.dto.request;

import lombok.Data;
import java.util.List;

@Data
public class TourPriceBatchRequest {
    private List<Long> ids;
}
