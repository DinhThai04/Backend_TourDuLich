package com.example.tour_service.operations;

import com.example.tour_service.dto.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/tour-departures/{departureId}/meeting-point")
@RequiredArgsConstructor
public class MeetingPointController {
    private final MeetingPointService meetingPoints;

    @GetMapping
    public ApiResponse<DepartureMeetingPoint> get(@PathVariable int departureId,
            @RequestParam(required = false) Integer tourId) {
        return ApiResponse.<DepartureMeetingPoint>builder().result(meetingPoints.get(departureId, tourId)).build();
    }

    @GetMapping("/options")
    public ApiResponse<List<DepartureMeetingPoint>> options(@PathVariable int departureId,
            @RequestParam(required = false) Integer tourId) {
        return ApiResponse.<List<DepartureMeetingPoint>>builder().result(meetingPoints.options(departureId, tourId)).build();
    }

    @PutMapping
    public ApiResponse<DepartureMeetingPoint> put(@PathVariable int departureId, @RequestBody DepartureMeetingPoint input) {
        return ApiResponse.<DepartureMeetingPoint>builder().result(meetingPoints.put(departureId, input)).build();
    }

    @PutMapping("/options")
    public ApiResponse<List<DepartureMeetingPoint>> replace(@PathVariable int departureId,
            @RequestBody List<DepartureMeetingPoint> input) {
        return ApiResponse.<List<DepartureMeetingPoint>>builder().result(meetingPoints.replace(departureId, input)).build();
    }
}
