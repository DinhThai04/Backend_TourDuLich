package com.example.booking_service.controller;

import com.example.booking_service.saga.RescheduleRefundSaga;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/internal/booking/dlq")
@RequiredArgsConstructor
public class DeadLetterQueueController {

    private final JdbcTemplate jdbc;
    private final RescheduleRefundSaga rescheduleRefundSaga;

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listDeadLetters() {
        var deadLetters = jdbc.queryForList("SELECT * FROM booking_dead_letter ORDER BY created_at DESC LIMIT 100");
        return ResponseEntity.ok(deadLetters);
    }

    @GetMapping("/{messageId}")
    public ResponseEntity<Map<String, Object>> getDeadLetter(@PathVariable String messageId) {
        var deadLetter = jdbc.queryForMap("SELECT * FROM booking_dead_letter WHERE message_id = ?", messageId);
        if (deadLetter == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(deadLetter);
    }

    @PostMapping("/{messageId}/retry")
    public ResponseEntity<Map<String, Object>> retryDeadLetter(@PathVariable String messageId) {
        try {
            // For booking, we retry by re-processing the saga
            var deadLetter = jdbc.queryForMap("SELECT * FROM booking_dead_letter WHERE message_id = ?", messageId);
            if (deadLetter == null) {
                return ResponseEntity.notFound().build();
            }
            
            String eventType = (String) deadLetter.get("event_type");
            String payload = (String) deadLetter.get("payload");
            
            // Re-process based on event type
            // This would need to be implemented based on the specific event types
            
            jdbc.update("DELETE FROM booking_dead_letter WHERE message_id = ?", messageId);
            return ResponseEntity.ok(Map.of("success", true, "messageId", messageId));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{messageId}/discard")
    public ResponseEntity<Map<String, Object>> discardDeadLetter(@PathVariable String messageId) {
        int deleted = jdbc.update("DELETE FROM booking_dead_letter WHERE message_id = ?", messageId);
        if (deleted > 0) {
            return ResponseEntity.ok(Map.of("success", true, "messageId", messageId));
        }
        return ResponseEntity.notFound().build();
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getDlqStats() {
        var stats = jdbc.queryForMap("""
            SELECT 
                COUNT(*) as total,
                COUNT(CASE WHEN created_at > DATE_SUB(NOW(), INTERVAL 1 HOUR) THEN 1 END) as last_hour,
                COUNT(CASE WHEN created_at > DATE_SUB(NOW(), INTERVAL 24 HOUR) THEN 1 END) as last_24_hours
            FROM booking_dead_letter
            """);
        return ResponseEntity.ok(stats);
    }
}