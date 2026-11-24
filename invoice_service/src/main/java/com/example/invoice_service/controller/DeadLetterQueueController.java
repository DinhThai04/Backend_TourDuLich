package com.example.invoice_service.controller;

import com.example.invoice_service.inbox.InboxConsumer;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/internal/invoice/dlq")
@RequiredArgsConstructor
public class DeadLetterQueueController {

    private final JdbcTemplate jdbc;
    private final InboxConsumer inboxConsumer;

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listDeadLetters() {
        var deadLetters = jdbc.queryForList("SELECT * FROM invoice_dead_letter ORDER BY created_at DESC LIMIT 100");
        return ResponseEntity.ok(deadLetters);
    }

    @GetMapping("/{messageId}")
    public ResponseEntity<Map<String, Object>> getDeadLetter(@PathVariable String messageId) {
        var deadLetter = jdbc.queryForMap("SELECT * FROM invoice_dead_letter WHERE message_id = ?", messageId);
        if (deadLetter == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(deadLetter);
    }

    @PostMapping("/{messageId}/retry")
    public ResponseEntity<Map<String, Object>> retryDeadLetter(@PathVariable String messageId) {
        try {
            inboxConsumer.retryDeadLetter(messageId);
            return ResponseEntity.ok(Map.of("success", true, "messageId", messageId));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{messageId}/discard")
    public ResponseEntity<Map<String, Object>> discardDeadLetter(@PathVariable String messageId) {
        int deleted = jdbc.update("DELETE FROM invoice_dead_letter WHERE message_id = ?", messageId);
        if (deleted > 0) {
            return ResponseEntity.ok(Map.of("success", true, "messageId", messageId));
        }
        return ResponseEntity.notFound().build();
    }

    @PostMapping("/retry-all")
    public ResponseEntity<Map<String, Object>> retryAllDeadLetters() {
        var deadLetters = jdbc.queryForList("SELECT message_id FROM invoice_dead_letter");
        int success = 0;
        int failed = 0;
        
        for (var dl : deadLetters) {
            String messageId = (String) dl.get("message_id");
            try {
                inboxConsumer.retryDeadLetter(messageId);
                success++;
            } catch (Exception e) {
                failed++;
            }
        }
        
        return ResponseEntity.ok(Map.of("success", success, "failed", failed, "total", deadLetters.size()));
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getDlqStats() {
        var stats = jdbc.queryForMap("""
            SELECT 
                COUNT(*) as total,
                COUNT(CASE WHEN created_at > DATE_SUB(NOW(), INTERVAL 1 HOUR) THEN 1 END) as last_hour,
                COUNT(CASE WHEN created_at > DATE_SUB(NOW(), INTERVAL 24 HOUR) THEN 1 END) as last_24_hours
            FROM invoice_dead_letter
            """);
        return ResponseEntity.ok(stats);
    }
}