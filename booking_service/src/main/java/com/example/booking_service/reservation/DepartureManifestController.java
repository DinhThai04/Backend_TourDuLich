package com.example.booking_service.reservation;

import com.example.booking_service.dto.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/operations/departures")
public class DepartureManifestController {
    public record Mark(@NotNull DepartureManifestService.Attendance status) {}
    public record Complete(@NotBlank @Size(max = 500) String note) {}

    public record Assignment(@Positive Integer accountId) {}

    private final DepartureManifestService service;

    public DepartureManifestController(DepartureManifestService service) {
        this.service = service;
    }

    @GetMapping("/{departureId}/manifest")
    public ApiResponse<DepartureManifestService.Manifest> get(@PathVariable int departureId) {
        return response(service.get(departureId));
    }

    @PutMapping("/{departureId}/manifest/passengers/{customerId}")
    public ApiResponse<DepartureManifestService.Manifest> mark(@PathVariable int departureId,
            @PathVariable int customerId, @Valid @RequestBody Mark request) {
        return response(service.mark(departureId, customerId, request.status()));
    }

    @PutMapping("/{departureId}/manifest/completion")
    public ApiResponse<DepartureManifestService.Manifest> complete(@PathVariable int departureId,
            @Valid @RequestBody Complete request) {
        return response(service.complete(departureId, request.note()));
    }

    @GetMapping("/assigned")
    public ApiResponse<List<Integer>> assigned() {
        return ApiResponse.<List<Integer>>builder().result(service.assignedDepartures()).build();
    }

    @PutMapping("/{departureId}/manifest/guide")
    public ApiResponse<DepartureManifestService.Manifest> assign(@PathVariable int departureId,
            @Valid @RequestBody Assignment request) {
        return response(service.assign(departureId, request.accountId()));
    }

    private ApiResponse<DepartureManifestService.Manifest> response(DepartureManifestService.Manifest manifest) {
        return ApiResponse.<DepartureManifestService.Manifest>builder().result(manifest).build();
    }
}
