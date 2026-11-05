package com.example.booking_service.notification;

import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Local visual review artifact only, no network/SMTP/database. */
@EnabledIfEnvironmentVariable(named = "MAIL_RENDER_PREVIEWS", matches = "true")
class MailPreviewTest {
    @Test void renderReviewCopies() throws Exception {
        var images=org.mockito.Mockito.mock(MailTourImageResolver.class);
        org.mockito.Mockito.when(images.findForDeparture(22)).thenReturn("https://res.cloudinary.com/xxcku2am/image/upload/v1791434013/tour12_01.jpg");
        var renderer = new MailTemplateRenderer(MailTemplateRendererTest.engine(),
                JsonMapper.builder().findAndAddModules().build(), new MailProperties(), images);
        var output = Path.of("target/mail-preview"); Files.createDirectories(output);
        for (var type : MailEventType.values()) {
            boolean test = type == MailEventType.TEST_MAIL;
            boolean receipt = type == MailEventType.PAYMENT_SUCCEEDED;
            var payload = new MailPayload(1, "Danh", test ? "Kiểm tra hệ thống email" : "Hà Nội – Hoa Lư – Tam Cốc – Tràng An",
                    test ? null : 22, test ? null : LocalDateTime.of(2026,11,20,6,0), test ? 0 : 1,
                    receipt ? BigDecimal.valueOf(3000000) : null, receipt ? "DEMO-REVIEW-42" : null,
                    receipt ? LocalDateTime.of(2026,10,14,8,0) : null,
                    type == MailEventType.BOOKING_CREATED || type == MailEventType.PAYMENT_REMINDER ? LocalDateTime.of(2026,10,20,8,0) : null, 5,
                    type == MailEventType.PAYMENT_REMINDER ? LocalDateTime.of(2026,10,19,8,0) :
                    type == MailEventType.DEPARTURE_REMINDER ? LocalDateTime.of(2026,11,15,8,0) : null);
            var item = new MailOutboxItem(0, type, test ? null : 42, test ? null : 2,
                    "tester@example.com", "{}", 0, "preview");
            String logo=Path.of("src/main/resources/templates/mail/assets/logo.png").toAbsolutePath().toUri().toString();
            Files.writeString(output.resolve(type.name().toLowerCase().replace('_','-') + ".html"),
                    renderer.render(item, payload).html().replace("cid:sgu-tour-logo",logo));
        }
    }
}
