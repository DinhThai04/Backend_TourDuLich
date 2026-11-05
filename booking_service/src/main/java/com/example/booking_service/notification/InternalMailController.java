package com.example.booking_service.notification;

import com.example.booking_service.dto.response.ApiResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/internal/mail/reminders")
public class InternalMailController {
    public record Input(Boolean dryRun,Integer batchSize,Integer afterBookingId) {}
    private final ReminderPlanner planner;
    public InternalMailController(ReminderPlanner planner) { this.planner=planner; }
    @PostMapping("/dispatch")
    public ResponseEntity<ApiResponse<ReminderPlanner.Result>> dispatch(@RequestBody(required=false) Input input) {
        var request=input==null ? ReminderPlanner.Request.preview() : new ReminderPlanner.Request(
                input.dryRun()==null || input.dryRun(),input.batchSize()==null ? 100 : input.batchSize(),
                input.afterBookingId()==null ? 0 : input.afterBookingId());
        var result=planner.dispatch(request);
        // Always 200 for a completed scan: per-booking failures are reported in the payload so the
        // caller keeps paginating. Infrastructure failures still propagate as 5xx for retry.
        return ResponseEntity.ok(ApiResponse.<ReminderPlanner.Result>builder()
                .code(result.failed()==0 ? 1000 : 1500).message(result.failed()>0 ? "Reminder planning completed with per-booking failures; inspect backend log"
                    : result.invalidRecipients()>0 ? "Reminder scan completed with invalid recipients skipped; inspect backend log"
                    : result.dryRun() ? "Preview only; no mail queued" : "Reminder planning completed; SMTP runs separately")
                .result(result).build());
    }
}
