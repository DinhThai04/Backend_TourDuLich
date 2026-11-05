package com.example.booking_service.notification;

import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MailTemplateRendererTest {
    static TemplateEngine engine() {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/"); resolver.setSuffix(".html"); resolver.setCharacterEncoding("UTF-8");
        var engine = new org.thymeleaf.spring6.SpringTemplateEngine(); engine.setTemplateResolver(resolver); return engine;
    }
    MailProperties props = new MailProperties();
    { props.setFrom("support@example.com"); }
    MailTourImageResolver images = mock(MailTourImageResolver.class);
    MailTemplateRenderer renderer = new MailTemplateRenderer(engine(), JsonMapper.builder().findAndAddModules().build(), props, images);
    MailOutboxItem item(MailEventType type) { return new MailOutboxItem(1, type, 42, 2, "tester@example.com", "{}", 0, "claim"); }
    MailPayload payload() {
        return new MailPayload(1, "Danh <script>alert(1)</script>", "Đà Nẵng & Hội An", 15,
                LocalDateTime.of(2026, 11, 20, 6, 0), 2, BigDecimal.valueOf(3000000),
                "txn-test-42", LocalDateTime.of(2026, 10, 14, 8, 0), null, 10);
    }
    @ParameterizedTest @EnumSource(MailEventType.class)
    void allTemplatesRenderVietnameseWithEscapedNames(MailEventType type) {
        var mail = renderer.render(item(type), payload());
        assertTrue(mail.html().contains("Xin chào"));
        assertFalse(mail.html().contains("<script>")); assertTrue(mail.html().contains("&lt;script&gt;"));
        assertTrue(mail.html().contains("Đà Nẵng &amp; Hội An"));
        assertTrue(mail.html().contains("support@example.com"));
        assertTrue(mail.text().contains("20/11/2026"));
    }
    @Test void receiptUsesVndRatherThanProviderHundredths() {
        var mail = renderer.render(item(MailEventType.PAYMENT_SUCCEEDED), payload());
        assertTrue(mail.text().contains("3.000.000 VND"));
        assertFalse(mail.text().contains("300.000.000"));
        assertTrue(mail.html().contains("txn-test-42"));
        assertFalse(mail.html().contains("Kiểm tra và tiếp tục thanh toán"));
    }
    @Test void proposedScheduleDoesNotClaimConsentAndRefundDoesNotClaimBankApi() {
        var old=payload();
        var proposed=new MailPayload(1,old.customerName(),old.tourTitle(),15,old.departureAt(),2,null,null,null,null,null,null,
            20L,old.departureAt().minusDays(5),"Bão lớn <script>unsafe</script>","PENDING",null);
        var mail=renderer.render(item(MailEventType.BOOKING_CHANGE_UPDATED),proposed);
        assertTrue(mail.text().contains("chưa phải xác nhận chuyển chỗ"));assertTrue(mail.text().contains("Lịch gốc:"));
        assertFalse(mail.html().contains("<script>unsafe</script>"));
        var refunded=new MailPayload(1,"Danh","Tour",15,old.departureAt(),2,null,null,null,null,null,null,
            20L,old.departureAt().minusDays(5),"Bão lớn","REFUNDED","BANK-123");
        assertTrue(renderer.render(item(MailEventType.BOOKING_CHANGE_UPDATED),refunded).text().contains("ghi nhận hoàn tiền thủ công"));
    }
    @Test void sguBrandAndLogoAreShownWithoutTestBannerWhileTestModeStaysEnabled() {
        var mail=renderer.render(item(MailEventType.PAYMENT_SUCCEEDED),payload());
        assertTrue(props.isTestMode());assertTrue(mail.subject().startsWith("[SGU Tour]"));
        assertTrue(mail.html().contains("cid:sgu-tour-logo"));assertTrue(mail.text().contains("SGU Tour"));
        assertFalse(mail.html().contains("EMAIL KIỂM THỬ"));assertFalse(mail.html().contains("Globe Explorer"));
    }
    @Test void acknowledgementReceiptAndDepartureReminderUseTheBookedTourImage() {
        String image="https://res.cloudinary.com/xxcku2am/image/upload/v1791434013/tour12_01.jpg";
        when(images.findForDeparture(15)).thenReturn(image);
        for(var type:new MailEventType[]{MailEventType.BOOKING_CREATED,MailEventType.PAYMENT_SUCCEEDED,MailEventType.DEPARTURE_REMINDER}) {
            var mail=renderer.render(item(type),payload());assertTrue(mail.html().contains(image));
            assertTrue(mail.html().contains("alt=\"Đà Nẵng &amp; Hội An\""));
        }
    }
    @Test void absentImageAndCancellationStillRenderCompleteInformation() {
        var receipt=renderer.render(item(MailEventType.PAYMENT_SUCCEEDED),payload());
        assertFalse(receipt.html().contains("res.cloudinary.com"));assertTrue(receipt.text().contains("3.000.000 VND"));
        clearInvocations(images);
        renderer.render(item(MailEventType.BOOKING_CANCELLED),payload());
        renderer.render(item(MailEventType.BOOKING_EXPIRED),payload());
        verifyNoInteractions(images);
    }
    @Test void acknowledgementDoesNotClaimUnimplementedReservation() {
        var mail = renderer.render(item(MailEventType.BOOKING_CREATED), payload());
        assertTrue(mail.text().contains("chưa có hạn giữ chỗ"));
        assertTrue(mail.html().contains("/invoice/booking/42"));
    }
    @Test void reservedAcknowledgementShowsExactSeatsAndDeadline() {
        var old = payload();
        var reserved = new MailPayload(1,old.customerName(),old.tourTitle(),old.departureId(),old.departureAt(),2,
                null,null,null,LocalDateTime.of(2026,11,7,8,0),null);
        var mail=renderer.render(item(MailEventType.BOOKING_CREATED),reserved);
        assertTrue(mail.text().contains("giữ 2 chỗ"));
        assertTrue(mail.text().contains("08:00 ngày 07/11/2026"));
        assertTrue(mail.text().contains("booking sẽ được hủy"));
    }
    @Test void payloadVersionsAndMissingReceiptDataAreRejected() {
        var invalid = new MailOutboxItem(1, MailEventType.TEST_MAIL, null, null, "tester@example.com", "{\"version\":2}", 0, "claim");
        assertThrows(IllegalArgumentException.class, () -> renderer.decode(invalid));
        assertThrows(IllegalArgumentException.class, () -> renderer.decode(item(MailEventType.PAYMENT_SUCCEEDED)));
    }
    @Test void addressAndBaseUrlRejectHeaderInjectionAndUnsafeLinks() {
        assertThrows(IllegalArgumentException.class, () -> MailOutboxService.validateAddress("x@gmail.com\r\nBcc:other@gmail.com"));
        assertThrows(IllegalArgumentException.class, () -> MailOutboxService.validateAddress("a@gmail.com,b@gmail.com"));
        props.setFrontendBaseUrl("javascript:alert(1)"); assertThrows(IllegalArgumentException.class, props::validate);
    }
    @Test void mimeMessageContainsUtf8PlainTextAndHtmlAndOnlyOneRecipient() throws Exception {
        props.setEnabled(true);
        // Test mode only lets the configured mailbox through, so the fixture recipient must be it.
        props.setTestRecipient("tester@example.com");
        var smtp = mock(JavaMailSender.class);
        when(smtp.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        var sender = new SpringMailSender(smtp, props);
        sender.send("tester@example.com", renderer.render(item(MailEventType.PAYMENT_SUCCEEDED), payload()));
        var captured = ArgumentCaptor.forClass(MimeMessage.class);
        verify(smtp).send(captured.capture());
        MimeMessage mail = captured.getValue(); mail.saveChanges();
        assertEquals(1, mail.getAllRecipients().length);
        assertEquals("tester@example.com", mail.getAllRecipients()[0].toString());
        assertTrue(mail.getSubject().contains("thanh toán"));
        var bytes = new java.io.ByteArrayOutputStream(); mail.writeTo(bytes);
        String wire = bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(wire.contains("multipart/alternative")); assertTrue(wire.contains("text/plain"));
        assertTrue(wire.contains("text/html")); assertTrue(wire.toUpperCase().contains("UTF-8"));
        assertTrue(wire.contains("Content-ID: <sgu-tour-logo>"));assertTrue(wire.contains("image/png"));
    }
    @Test void disabledAndOtherRecipientsCannotReachSmtp() {
        var smtp = mock(JavaMailSender.class);
        var sender = new SpringMailSender(smtp, props);
        var mail = renderer.render(item(MailEventType.TEST_MAIL), MailPayload.test());
        assertThrows(IllegalStateException.class, () -> sender.send("tester@example.com", mail));
        props.setEnabled(true);
        assertThrows(IllegalArgumentException.class, () -> sender.send("user1@gmail.com", mail));
        verifyNoInteractions(smtp);
    }
}
