package com.example.notification_service.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class TicketReceiptMailTest {
    @Test
    void rendersTourPhotoTicketDetailsAndAuthenticatedBookingLink() {
        var ticket = new TicketReceiptMail.Ticket("Hà Nội & Ninh Bình", "SGU-T15-20261129",
                "2026-11-29T07:00", "2026-12-01T18:00",
                "https://res.cloudinary.com/demo/image/upload/tour.jpg", 2, new BigDecimal("1350000"),
                new TicketReceiptMail.Pickup("Điểm A", "06:15"));
        var mail = TicketReceiptMail.render(10, ticket, "https://example.test");

        assertThat(mail.subject()).isEqualTo("SGU Tour · Thanh toán thành công · Đơn #10");
        assertThat(mail.html()).contains("&amp;");
        assertThat(org.springframework.web.util.HtmlUtils.htmlUnescape(mail.html())).contains("Hà Nội & Ninh Bình", "SGU-T15-20261129", "07:00 29/11/2026",
                "tour.jpg", "1.350.000", "06:15 · Điểm A", "https://example.test/profile?bookingId=10#don-dat-tour");
        assertThat(mail.text()).contains("Hành khách: 2", "Mở vé điện tử");
    }
}
