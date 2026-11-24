package com.example.notification_service.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import com.example.notification_service.service.NotificationDeadLetterService;
import org.springframework.dao.EmptyResultDataAccessException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/internal/notification/dlq")
@RequiredArgsConstructor
public class DeadLetterQueueController {

    private final JdbcTemplate jdbc;
    private final NotificationDeadLetterService deadLetters;

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listDeadLetters() {
        var deadLetters = jdbc.queryForList("""
                SELECT message_id,event_type,error,attempts,created_at
                FROM notification_dead_letter ORDER BY created_at DESC LIMIT 100
                """);
        return ResponseEntity.ok(deadLetters);
    }

    @GetMapping("/{messageId}")
    public ResponseEntity<Map<String, Object>> getDeadLetter(@PathVariable String messageId) {
        try {
            return ResponseEntity.ok(jdbc.queryForMap(
                    "SELECT message_id,event_type,error,attempts,created_at FROM notification_dead_letter WHERE message_id = ?", messageId));
        } catch (EmptyResultDataAccessException missing) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{messageId}/retry")
    public ResponseEntity<Map<String, Object>> retryDeadLetter(@PathVariable String messageId) {
        try {
            if (!deadLetters.retry(messageId)) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(Map.of("success", true, "messageId", messageId));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Could not queue the dead-letter retry"));
        }
    }

    @PostMapping("/{messageId}/discard")
    public ResponseEntity<Map<String, Object>> discardDeadLetter(@PathVariable String messageId) {
        if (deadLetters.discard(messageId)) {
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
