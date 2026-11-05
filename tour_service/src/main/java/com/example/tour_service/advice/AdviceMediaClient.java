package com.example.tour_service.advice;

import com.example.tour_service.dto.response.TourFileResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import java.util.List;

@FeignClient(name="advice-media", url="${app.advice.media-url:http://localhost:8087/file}",
        configuration=AdviceFeignConfiguration.class)
public interface AdviceMediaClient {
    @GetMapping("/media/tours") List<TourFileResponse> media(@RequestParam("tourIds") List<String> tourIds);
}
