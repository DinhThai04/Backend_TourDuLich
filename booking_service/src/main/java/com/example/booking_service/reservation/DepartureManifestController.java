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
    public record Mark(@NotNull DepartureManifestService.Attendance status, @Size(max = 500) String note) {}
    public record Confirmation(java.time.LocalDateTime actualDepartureAt, @Size(max = 500) String reason) {}
    public record Complete(@NotBlank @Size(max = 500) String note, boolean override) {}

    public record Assignment(@Positive Integer accountId, boolean forceOverride, @Size(max = 500) String reason) {}
    public record ReportRequest(boolean submit, @NotBlank @Size(max = 2000) String summary,
                                @Size(max = 1000) String incidentNote, @Size(max = 1000) String handoverNote) {}
    public record ReviewRequest(@NotNull Boolean approve, @Size(max = 500) String comment) {}
    public record ProgressRequest(@NotNull DepartureManifestService.Progress status, @Size(max = 500) String note) {}

    private final DepartureManifestService service;

    private final DepartureReadiness readiness;

    public DepartureManifestController(DepartureManifestService service, DepartureReadiness readiness) {
        this.service = service;
        this.readiness = readiness;
    }

    @GetMapping("/alerts")
    public ApiResponse<DepartureReadiness.Page> alerts(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Integer bookingId,
            @RequestParam(required = false)
            @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
            java.time.LocalDate day) {
        return ApiResponse.<DepartureReadiness.Page>builder().result(readiness.alerts(page, size, bookingId, day)).build();
    }

    @GetMapping("/{departureId}/manifest")
    public ApiResponse<DepartureManifestService.Manifest> get(@PathVariable int departureId) {
        return response(service.get(departureId));
    }

    @PutMapping("/{departureId}/manifest/passengers/{customerId}")
    public ApiResponse<DepartureManifestService.Manifest> mark(@PathVariable int departureId,
            @PathVariable int customerId, @Valid @RequestBody Mark request) {
        return response(service.mark(departureId, customerId, request.status(), request.note()));
    }

    @PostMapping("/{departureId}/confirm-departure")
    public ApiResponse<DepartureManifestService.Manifest> confirmDeparture(@PathVariable int departureId,
            @Valid @RequestBody Confirmation request) {
        return response(service.confirmDeparture(departureId, request.actualDepartureAt(), request.reason()));
    }

    @PutMapping("/{departureId}/manifest/completion")
    public ApiResponse<DepartureManifestService.Manifest> complete(@PathVariable int departureId,
            @Valid @RequestBody Complete request) {
        return response(service.complete(departureId, request.note(), request.override()));
    }

    @GetMapping("/assigned")
    public ApiResponse<List<Integer>> assigned() {
        return ApiResponse.<List<Integer>>builder().result(service.assignedDepartures()).build();
    }

    @PutMapping("/{departureId}/manifest/guide")
    public ApiResponse<DepartureManifestService.Manifest> assign(@PathVariable int departureId,
            @Valid @RequestBody Assignment request) {
        return response(service.assign(departureId, request.accountId(), request.forceOverride(), request.reason()));
    }

    @GetMapping("/{departureId}/manifest/guide-conflicts")
    public ApiResponse<java.util.Map<Integer, Integer>> guideConflicts(@PathVariable int departureId,
            @RequestParam List<Integer> guideIds) {
        return ApiResponse.<java.util.Map<Integer, Integer>>builder().result(service.guideConflicts(departureId, guideIds)).build();
    }

    @GetMapping("/{departureId}/manifest/contacts")
    public ApiResponse<java.util.Map<Integer, String>> contacts(@PathVariable int departureId) {
        return ApiResponse.<java.util.Map<Integer, String>>builder().result(service.contacts(departureId)).build();
    }

    @GetMapping("/{departureId}/manifest/report")
    public ApiResponse<DepartureManifestService.Report> report(@PathVariable int departureId) {
        return ApiResponse.<DepartureManifestService.Report>builder().result(service.report(departureId)).build();
    }

    @PutMapping("/{departureId}/manifest/report")
    public ApiResponse<DepartureManifestService.Report> saveReport(@PathVariable int departureId,
            @Valid @RequestBody ReportRequest request) {
        return ApiResponse.<DepartureManifestService.Report>builder().result(service.saveReport(departureId,
                request.submit(), request.summary().strip(), request.incidentNote(), request.handoverNote())).build();
    }

    @PostMapping("/{departureId}/manifest/report/review")
    public ApiResponse<DepartureManifestService.Report> review(@PathVariable int departureId,
            @Valid @RequestBody ReviewRequest request) {
        return ApiResponse.<DepartureManifestService.Report>builder()
                .result(service.review(departureId, request.approve(), request.comment())).build();
    }

    @GetMapping("/{departureId}/manifest/itinerary")
    public ApiResponse<List<DepartureManifestService.Checkpoint>> checkpoints(@PathVariable int departureId) {
        return ApiResponse.<List<DepartureManifestService.Checkpoint>>builder().result(service.checkpoints(departureId)).build();
    }

    @PatchMapping("/{departureId}/manifest/itinerary/{itineraryId}")
    public ApiResponse<List<DepartureManifestService.Checkpoint>> progress(@PathVariable int departureId,
            @PathVariable int itineraryId, @Valid @RequestBody ProgressRequest request) {
        return ApiResponse.<List<DepartureManifestService.Checkpoint>>builder()
                .result(service.progress(departureId, itineraryId, request.status(), request.note())).build();
    }

    private ApiResponse<DepartureManifestService.Manifest> response(DepartureManifestService.Manifest manifest) {
        return ApiResponse.<DepartureManifestService.Manifest>builder().result(manifest).build();
    }
}
