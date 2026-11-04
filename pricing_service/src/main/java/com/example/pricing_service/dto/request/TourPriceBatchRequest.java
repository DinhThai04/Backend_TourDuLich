package com.example.pricing_service.dto.request;

import lombok.Data;
import java.util.List;

@Data
public class TourPriceBatchRequest {
    @jakarta.validation.constraints.NotNull(message="Thiếu danh sách mã bảng giá")
    @jakarta.validation.constraints.Size(min=1, max=100, message="Cần từ 1 đến 100 mã bảng giá")
    private List<@jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Positive Long> ids;
}
