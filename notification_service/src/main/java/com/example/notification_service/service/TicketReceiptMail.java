package com.example.notification_service.service;

import com.example.notification_service.domain.MailOutboxItem;
import com.example.notification_service.domain.MailPayload;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.URI;
import java.text.NumberFormat;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import static org.springframework.web.util.HtmlUtils.htmlEscape;

@Component
public class TicketReceiptMail {
    private static final DateTimeFormatter VIETNAM_DATE = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");
    private final RestClient booking;
    private final String publicBaseUrl;
    private final String ticketKey;

    public TicketReceiptMail(
            @Value("${app.services.booking-base-url:http://booking-service:8081/booking}") String bookingBase,
            @Value("${NOTIFICATION_INTERNAL_KEY:}") String ticketKey,
            @Value("${MAIL_FRONTEND_BASE_URL:http://localhost:5173}") String publicBaseUrl) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(4));
        this.booking = RestClient.builder().baseUrl(bookingBase).requestFactory(factory).build();
        this.ticketKey = ticketKey;
        this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
    }

    public MailPayload enrich(MailOutboxItem item, MailPayload original) {
        if (item.bookingId() == null || !("PAYMENT_SUCCEEDED".equals(item.eventType())
                || original.subject().startsWith("SGU Tour - Payment received for booking #"))) return original;
        var ticket = booking.get().uri("/internal/booking/bookings/{id}/ticket-mail", item.bookingId())
                .header("X-Internal-Notification-Key", ticketKey).retrieve().body(Ticket.class);
        if (ticket == null) throw new IllegalStateException("Ticket data is unavailable");
        return render(item.bookingId(), ticket, publicBaseUrl);
    }

    static MailPayload render(int id, Ticket ticket, String base) {
        String link = base + "/profile?bookingId=" + id + "#don-dat-tour";
        String amount = ticket.amountVnd() == null ? "Xem trong hóa đơn"
                : NumberFormat.getIntegerInstance(Locale.forLanguageTag("vi-VN")).format(ticket.amountVnd()) + " ₫";
        String departure = date(ticket.departure());
        String returns = date(ticket.returns());
        String pickup = ticket.pickup() == null ? "Xem thông tin điểm đón trong hồ sơ đặt chỗ"
                : (ticket.pickup().arrivalTime() == null ? "" : ticket.pickup().arrivalTime() + " · ") + ticket.pickup().address();
        String subject = "SGU Tour · Thanh toán thành công · Đơn #" + id;
        String cover = safeCover(ticket.cover()) ? """
                <img src="%s" alt="Ảnh tour" width="600" style="width:100%%;height:auto;display:block">
                """.formatted(htmlEscape(ticket.cover())) : "";
        String html = """
                <div lang="vi" style="background:#edf4f2;padding:20px 10px;font-family:Arial,sans-serif;color:#172b2a">
                <div style="max-width:600px;margin:0 auto;background:#fff;border:1px solid #d7e5e1;border-radius:14px;overflow:hidden">
                <div style="background:#134c45;color:white;padding:22px;font-size:22px;font-weight:bold">SGU Tour</div>
                %s
                <div style="padding:24px">
                <p style="color:#166454;font-weight:bold">Thanh toán thành công · Đơn #%d</p>
                <h1 style="font-size:24px;line-height:1.25;margin:10px 0 18px">%s</h1>
                <table cellpadding="7" cellspacing="0" style="width:100%%;background:#f1f7f5;border-radius:8px">
                <tr><th align="left">Mã chuyến</th><td>%s</td></tr>
                <tr><th align="left">Khởi hành</th><td>%s</td></tr>
                <tr><th align="left">Kết thúc</th><td>%s</td></tr>
                <tr><th align="left">Hành khách</th><td>%d</td></tr>
                <tr><th align="left">Điểm đón</th><td>%s</td></tr>
                <tr><th align="left">Tổng tiền</th><td><strong>%s</strong></td></tr>
                </table>
                <p style="margin:24px 0"><a href="%s" style="background:#1e786a;color:white;padding:12px 18px;border-radius:8px;text-decoration:none;display:inline-block">Mở vé điện tử</a></p>
                <p style="color:#526361;font-size:14px">Đăng nhập để xem mã check-in và thông tin mới nhất của chuyến đi. Giờ hiển thị theo giờ Việt Nam.</p>
                </div></div></div>
                """.formatted(cover, id, htmlEscape(ticket.title()), htmlEscape(ticket.tourCode()),
                htmlEscape(departure), htmlEscape(returns), ticket.passengers(), htmlEscape(pickup), htmlEscape(amount), htmlEscape(link));
        String text = subject + "\n" + ticket.title() + "\nMã chuyến: " + ticket.tourCode() + "\nKhởi hành: "
                + departure + "\nKết thúc: " + returns + "\nHành khách: " + ticket.passengers() + "\nĐiểm đón: " + pickup
                + "\nTổng tiền: " + amount + "\nMở vé điện tử: " + link;
        return new MailPayload(1, subject, html, text);
    }

    private static boolean safeCover(String input) {
        if (input == null) return false;
        try {
            var url = URI.create(input);
            return "https".equals(url.getScheme()) && "res.cloudinary.com".equals(url.getHost())
                    && url.getUserInfo() == null && url.getPath().contains("/image/upload/");
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static String date(String value) {
        return value == null || value.isBlank() ? "Xem trên vé" : LocalDateTime.parse(value).format(VIETNAM_DATE);
    }

    record Ticket(String title, String tourCode, String departure, String returns, String cover,
                  int passengers, BigDecimal amountVnd, Pickup pickup) {}
    record Pickup(String address, String arrivalTime) {}
}
