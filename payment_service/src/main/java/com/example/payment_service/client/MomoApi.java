package com.example.payment_service.client;

import com.example.payment_service.dto.request.CreateMomoRequest;
import com.example.payment_service.dto.response.CreateMomoResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "momo", url = "${momo.end-point}")
public interface MomoApi
{
    @PostMapping("/create")
    CreateMomoResponse createMomoQr(@RequestBody CreateMomoRequest createMomoRequest);
}



