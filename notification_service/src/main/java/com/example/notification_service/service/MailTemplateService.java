package com.example.notification_service.service;

import com.example.notification_service.domain.MailPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.web.util.HtmlUtils;

@Service
@RequiredArgsConstructor
@Slf4j
public class MailTemplateService {

    private final JdbcTemplate jdbc;

    public Optional<MailTemplate> getTemplate(String code) {
        var rows = jdbc.queryForList("SELECT * FROM mail_template WHERE code = ?", code);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        var row = rows.getFirst();
        return Optional.of(new MailTemplate(
                (String) row.get("id"),
                (String) row.get("code"),
                (String) row.get("subject"),
                (String) row.get("body_html"),
                (String) row.get("body_text"),
                (String) row.get("variables")
        ));
    }

    @Transactional
    public MailTemplate createTemplate(String code, String subject, String bodyHtml, String bodyText, Map<String, Object> variables) {
        String id = java.util.UUID.randomUUID().toString();
        String variablesJson = variables != null ? toJson(variables) : null;
        
        jdbc.update("""
            INSERT INTO mail_template (id, code, subject, body_html, body_text, variables, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, id, code, subject, bodyHtml, bodyText, variablesJson, LocalDateTime.now(), LocalDateTime.now());
        
        return new MailTemplate(id, code, subject, bodyHtml, bodyText, variablesJson);
    }

    @Transactional
    public MailTemplate updateTemplate(String code, String subject, String bodyHtml, String bodyText, Map<String, Object> variables) {
        String variablesJson = variables != null ? toJson(variables) : null;
        
        jdbc.update("""
            UPDATE mail_template SET subject = ?, body_html = ?, body_text = ?, variables = ?, updated_at = ?
            WHERE code = ?
            """, subject, bodyHtml, bodyText, variablesJson, LocalDateTime.now(), code);
        
        return getTemplate(code).orElseThrow();
    }

    public MailPayload renderTemplate(String code, Map<String, Object> variables) {
        var template = getTemplate(code).orElseThrow(() -> new IllegalArgumentException("Template not found: " + code));
        return template.render(variables);
    }

    private String toJson(Map<String, Object> map) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(map);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize variables", e);
        }
    }

    public record MailTemplate(
            String id,
            String code,
            String subject,
            String bodyHtml,
            String bodyText,
            String variablesJson
    ) {
        public MailPayload render(Map<String, Object> variables) {
            String subject = renderString(this.subject, variables, false);
            if (subject.length() > 200 || subject.contains("\r") || subject.contains("\n")) {
                throw new IllegalArgumentException("Rendered mail subject is invalid");
            }
            String html = renderString(this.bodyHtml, variables, true);
            String text = this.bodyText != null ? renderString(this.bodyText, variables, false) : null;
            return new MailPayload(1, subject, html, text);
        }

        private String renderString(String template, Map<String, Object> variables, boolean htmlContext) {
            if (template == null) return null;
            String result = template;
            if (variables != null) {
                for (Map.Entry<String, Object> entry : variables.entrySet()) {
                    String key = "{{" + entry.getKey() + "}}";
                    String value = entry.getValue() != null ? entry.getValue().toString() : "";
                    if (htmlContext) {
                        value = HtmlUtils.htmlEscape(value);
                    }
                    result = result.replace(key, value);
                }
            }
            return result;
        }
    }
}
