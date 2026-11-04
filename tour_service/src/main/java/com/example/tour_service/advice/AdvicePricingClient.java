package com.example.tour_service.advice;

import com.example.tour_service.dto.request.ApiResponse;
import com.example.tour_service.dto.request.TourPriceBatchRequest;
import com.example.tour_service.dto.response.TourPriceResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import java.util.List;

@FeignClient(name="advice-pricing", url="${app.advice.pricing-url:http://localhost:8888/api/v1/pricing}",
        configuration=AdviceFeignConfiguration.class)
public interface AdvicePricingClient {
    @PostMapping("/prices/batch") ApiResponse<List<TourPriceResponse>> prices(@RequestBody TourPriceBatchRequest request);
}
