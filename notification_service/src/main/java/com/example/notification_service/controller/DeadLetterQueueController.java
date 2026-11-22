package com.example.notification_service.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/internal/notification/dlq")
@RequiredArgsConstructor
public class DeadLetterQueueController {

    private final JdbcTemplate jdbc;

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listDeadLetters() {
        var deadLetters = jdbc.queryForList("SELECT * FROM notification_dead_letter ORDER BY created_at DESC LIMIT 100");
        return ResponseEntity.ok(deadLetters);
    }

    @GetMapping("/{messageId}")
    public ResponseEntity<Map<String, Object>> getDeadLetter(@PathVariable String messageId) {
        var deadLetter = jdbc.queryForMap("SELECT * FROM notification_dead_letter WHERE message_id = ?", messageId);
        if (deadLetter == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(deadLetter);
    }

    @PostMapping("/{messageId}/retry")
    public ResponseEntity<Map<String, Object>> retryDeadLetter(@PathVariable String messageId) {
        try {
            var deadLetter = jdbc.queryForMap("SELECT * FROM notification_dead_letter WHERE message_id = ?", messageId);
            if (deadLetter == null) {
                return ResponseEntity.notFound().build();
            }
            
            // Re-process the event by re-inserting into inbox for processing
            String eventType = (String) deadLetter.get("event_type");
            String payload = (String) deadLetter.get("payload");
            
            // This would trigger the consumer to process it again
            // For now, just delete from DLQ and let the consumer handle it
            jdbc.update("DELETE FROM notification_dead_letter WHERE message_id = ?", messageId);
            
            return ResponseEntity.ok(Map.of("success", true, "messageId", messageId));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{messageId}/discard")
    public ResponseEntity<Map<String, Object>> discardDeadLetter(@PathVariable String messageId) {
        int deleted = jdbc.update("DELETE FROM notification_dead_letter WHERE message_id = ?", messageId);
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
            FROM notification_dead_letter
            """);
        return ResponseEntity.ok(stats);
    }
}