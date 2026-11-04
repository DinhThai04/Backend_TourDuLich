package com.example.booking_service.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

@Component
public class MailTemplateRenderer {
    public record RenderedMail(String subject, String text, String html) {}
    private final TemplateEngine engine;
    private final ObjectMapper mapper;
    private final MailProperties properties;
    private final MailTourImageResolver images;
    public MailTemplateRenderer(TemplateEngine engine, ObjectMapper mapper, MailProperties properties, MailTourImageResolver images) {
        this.engine = engine; this.mapper = mapper; this.properties = properties; this.images = images;
    }
    public MailPayload decode(MailOutboxItem item) {
        try {
            MailPayload payload = mapper.readValue(item.payload(), MailPayload.class);
            if (payload == null || payload.version() != 1) throw new IllegalArgumentException("Unsupported payload version");
            if (item.eventType() != MailEventType.TEST_MAIL && (payload.customerName() == null
                    || payload.tourTitle() == null || payload.departureAt() == null || payload.passengerCount() < 1))
                throw new IllegalArgumentException("Incomplete business email payload");
            if (item.eventType() == MailEventType.PAYMENT_SUCCEEDED && (payload.amountVnd() == null
                    || payload.amountVnd().signum() <= 0 || payload.amountVnd().stripTrailingZeros().scale() > 0
                    || payload.paidAt() == null || payload.transactionRef() == null || payload.transactionRef().isBlank()))
                throw new IllegalArgumentException("Incomplete payment receipt");
            if ((item.eventType()==MailEventType.PAYMENT_REMINDER || item.eventType()==MailEventType.DEPARTURE_REMINDER)
                    && payload.reminderAt()==null) throw new IllegalArgumentException("Missing reminder milestone");
            if(item.eventType()==MailEventType.BOOKING_CHANGE_UPDATED && (payload.changeId()==null || payload.changeId()<1
                || payload.oldDepartureAt()==null || payload.changeReason()==null || payload.changeReason().isBlank()
                || payload.changeStatus()==null || payload.changeStatus().isBlank()))
                throw new IllegalArgumentException("Incomplete schedule change snapshot");
            return payload;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("Malformed mail payload", e);
        }
    }

    public RenderedMail render(MailOutboxItem item, MailPayload payload) {
        String title = switch (item.eventType()) {
            case TEST_MAIL -> "Kiểm tra kết nối email thành công";
            case BOOKING_CREATED -> "Đã ghi nhận yêu cầu đặt tour của bạn";
            case PAYMENT_SUCCEEDED -> "Xác nhận thanh toán tour thành công";
            case BOOKING_CANCELLED -> "Xác nhận hủy booking";
            case BOOKING_EXPIRED -> "Booking đã hủy do chưa hoàn tất thanh toán";
            case PAYMENT_REMINDER -> "Nhắc thanh toán cho chuyến đi sắp tới";
            case DEPARTURE_REMINDER -> "Chuyến đi của bạn sắp khởi hành";
            case BOOKING_CHANGE_UPDATED -> "Cập nhật yêu cầu dời lịch chuyến đi";
        };
        String template = item.eventType().name().toLowerCase(Locale.ROOT).replace('_', '-');
        String root = properties.getFrontendBaseUrl().replaceAll("/+$", "");
        String paymentUrl = item.bookingId() == null ? root : root + "/invoice/booking/" + item.bookingId();
        String date = payload.departureAt() == null ? "" : format(payload.departureAt());
        String amount = payload.amountVnd() == null ? "" : vnd(payload.amountVnd());
        String explanation = switch (item.eventType()) {
            case BOOKING_CHANGE_UPDATED -> changeExplanation(payload);
            case TEST_MAIL -> "Đây là email kiểm tra do bạn yêu cầu. Không có booking hoặc giao dịch nào được tạo từ email này.";
            case BOOKING_CREATED -> "Yêu cầu đặt tour đã được ghi nhận. Trạng thái: Chưa thanh toán. "
                    + (payload.holdUntil() == null ? "Booking này chưa có hạn giữ chỗ được xác nhận; vui lòng kiểm tra trước khi thanh toán."
                    : "Chúng tôi đã giữ " + payload.passengerCount() + " chỗ đến " + format(payload.holdUntil())
                        + " (giờ Việt Nam). Vui lòng hoàn tất thanh toán trước thời điểm này. Nếu chưa thanh toán khi hết hạn, booking sẽ được hủy và chỗ sẽ được mở lại.");
            case PAYMENT_SUCCEEDED -> "Thanh toán của bạn đã được xác nhận thành công. Số tiền đã thanh toán: " + amount + ".";
            case BOOKING_CANCELLED -> "Booking đã được hủy. Email này không xác nhận hoàn tiền; nếu đã thanh toán, vui lòng liên hệ để được kiểm tra và hướng dẫn.";
            case BOOKING_EXPIRED -> "Booking của bạn chưa hoàn tất thanh toán và đã được hủy theo chính sách giữ chỗ. Chỗ đã được mở lại cho khách khác. "
                    + "Bạn có thể đặt lại nếu chuyến đi còn chỗ. Nếu ngân hàng đã trừ tiền, vui lòng liên hệ để chúng tôi đối soát; không thanh toán lại khi chưa được kiểm tra.";
            case PAYMENT_REMINDER -> "Booking của bạn hiện ở trạng thái Chưa thanh toán. Chúng tôi vẫn đang giữ "
                    + payload.passengerCount() + " chỗ đến " + (payload.holdUntil()==null ? "hạn thanh toán đã thông báo" : format(payload.holdUntil()) + " (giờ Việt Nam)")
                    + ". Vui lòng hoàn tất thanh toán trước hạn để xác nhận chuyến đi. Nếu chưa thanh toán khi hết hạn, booking sẽ tự hủy và chỗ sẽ được mở lại. "
                    + "Nếu bạn cần hỗ trợ hoặc đã thanh toán nhưng trạng thái chưa cập nhật, hãy trả lời email này để chúng tôi kiểm tra; vui lòng không thanh toán lại khi chưa được đối soát.";
            case DEPARTURE_REMINDER -> "Chuyến đi đã thanh toán của bạn sắp khởi hành. Vui lòng kiểm tra ngày và giờ bên dưới, "
                    + "chuẩn bị giấy tờ tùy thân, vật dụng cá nhân và sắp xếp thời gian di chuyển. Nếu cần xác nhận điểm tập trung hoặc có thay đổi kế hoạch, "
                    + "hãy liên hệ trước với chúng tôi qua email hỗ trợ. Cảm ơn bạn đã đồng hành, chúc bạn một chuyến đi thuận lợi!";
        };
        Context context = new Context(Locale.forLanguageTag("vi-VN"));
        context.setVariable("brand", properties.getBrand());
        context.setVariable("title", title);
        context.setVariable("name", payload.customerName() == null ? "bạn" : payload.customerName());
        context.setVariable("bookingId", item.bookingId());
        context.setVariable("tour", payload.tourTitle());
        context.setVariable("departure", date);
        context.setVariable("passengers", payload.passengerCount());
        context.setVariable("explanation", explanation);
        context.setVariable("oldDeparture",payload.oldDepartureAt()==null?null:format(payload.oldDepartureAt()));
        context.setVariable("isChange",item.eventType()==MailEventType.BOOKING_CHANGE_UPDATED);
        context.setVariable("transaction", payload.transactionRef());
        context.setVariable("paidAt", payload.paidAt() == null ? "" : format(payload.paidAt()));
        context.setVariable("paymentUrl", paymentUrl);
        context.setVariable("accountUrl", root + "/profile");
        context.setVariable("support", properties.getFrom());
        boolean showTourImage = item.eventType() == MailEventType.BOOKING_CREATED
                || item.eventType() == MailEventType.PAYMENT_SUCCEEDED || item.eventType() == MailEventType.DEPARTURE_REMINDER;
        context.setVariable("tourImage", showTourImage ? images.findForDeparture(payload.departureId()) : null);
        context.setVariable("showPayment", item.eventType() == MailEventType.BOOKING_CREATED
                || item.eventType() == MailEventType.PAYMENT_REMINDER);
        String text = "Xin chào " + (payload.customerName() == null ? "bạn" : payload.customerName()) + ",\n\n"
                + title + ".\n" + explanation + "\n"
                + (payload.oldDepartureAt()==null?"":"Lịch gốc: "+format(payload.oldDepartureAt())+"\n")
                + (item.bookingId() == null ? "" : "Mã booking: " + item.bookingId() + "\nTour: " + payload.tourTitle()
                    + "\nKhởi hành: " + date + " (giờ Việt Nam)\nSố hành khách: " + payload.passengerCount() + "\n")
                + (item.eventType() == MailEventType.PAYMENT_SUCCEEDED
                    ? "Mã giao dịch: " + payload.transactionRef() + "\nThời điểm thanh toán: " + format(payload.paidAt()) + "\n" : "")
                + "\nXem booking: " + root + "/profile\nHỗ trợ: " + properties.getFrom()
                + "\n\nCảm ơn bạn đã tin tưởng " + properties.getBrand() + ".";
        if (item.eventType() == MailEventType.PAYMENT_REMINDER || item.eventType() == MailEventType.BOOKING_CREATED)
            text += "\nTiếp tục thanh toán: " + paymentUrl;
        String subject = "[" + properties.getBrand() + "] " + title
                + (item.bookingId() == null ? "" : " — #" + item.bookingId());
        if (subject.contains("\r") || subject.contains("\n")) throw new IllegalArgumentException("Invalid mail subject");
        return new RenderedMail(subject, text, engine.process("mail/" + template, context));
    }
    private static String changeExplanation(MailPayload p) {
        String state=switch(String.valueOf(p.changeStatus())) {
            case "PENDING" -> "Chúng tôi đã ghi nhận đề xuất dời lịch. Vui lòng đăng nhập mục Lịch sử đặt chỗ để xem thông tin và lựa chọn. Lịch bên dưới là lịch đề xuất, chưa phải xác nhận chuyển chỗ. Việc chưa phản hồi không được coi là đồng ý.";
            case "ACCEPTED" -> "Lựa chọn đồng ý của bạn đã được ghi nhận. Shop sẽ kiểm tra sức chứa và xác nhận chuyển lịch; hiện booking vẫn chưa chuyển sang lịch đề xuất.";
            case "APPROVED" -> "Booking của bạn đã được chuyển thành công sang lịch khởi hành bên dưới. Chứng từ và số tiền đã thanh toán được giữ nguyên. Vui lòng xem lại thông tin chuyến đi trong tài khoản.";
            case "REFUNDED" -> "Shop đã ghi nhận hoàn tiền thủ công toàn bộ theo yêu cầu của bạn, tham chiếu: "+p.refundReference()+". Booking đã hủy. Đây là bản ghi của shop; vui lòng kiểm tra tài khoản nhận tiền và liên hệ nếu có sai lệch.";
            case "SUPPORT_REQUIRED" -> "Yêu cầu hỗ trợ của bạn đang được xử lý. Chưa có xác nhận chuyển lịch hoặc hoàn tiền. Vui lòng liên hệ shop để thống nhất phương án phù hợp.";
            case "CANCELLED_BY_SHOP" -> "Booking chưa thanh toán bị ảnh hưởng bởi sự cố đã được shop hủy và trả chỗ. Bạn có thể liên hệ để được hỗ trợ đặt lại.";
            default -> "Yêu cầu dời lịch này đã kết thúc hoặc được thay thế. Vui lòng xem trạng thái mới nhất trong tài khoản; lịch đề xuất cũ không còn là xác nhận cho chuyến đi.";
        };
        return state+" Lý do: "+p.changeReason();
    }
    private static String format(java.time.LocalDateTime value) {
        return value.format(DateTimeFormatter.ofPattern("HH:mm 'ngày' dd/MM/yyyy"));
    }
    private static String vnd(BigDecimal value) {
        return NumberFormat.getIntegerInstance(Locale.forLanguageTag("vi-VN")).format(value) + " VND";
    }
}
