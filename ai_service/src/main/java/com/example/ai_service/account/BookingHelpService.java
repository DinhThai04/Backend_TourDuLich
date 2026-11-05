package com.example.ai_service.account;

import com.example.ai_service.advisor.PolicyCatalog;
import com.example.ai_service.conversation.ChatFailure;
import com.example.ai_service.conversation.ChatModels.PolicyInfo;
import com.example.ai_service.security.AiInputPolicy;
import org.springframework.stereotype.Service;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/** Stateless, read-only answers from owner-scoped facts. No LanguageModel or ConversationStore dependency. */
@Service
public class BookingHelpService {
    public record Choice(int bookingId,String tourTitle,OffsetDateTime departureAt,String state) {}
    public record Help(boolean handled,String reply,Integer selectedBookingId,List<Choice> choices,
            OffsetDateTime checkedAt,PolicyInfo policy) {}
    private final BookingReader reader;
    private final PolicyCatalog policies;
    private static final ZoneId ZONE=ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter FORMAT=DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");
    public BookingHelpService(BookingReader reader,PolicyCatalog policies) { this.reader=reader;this.policies=policies; }
    public Help help(String message,Integer selected,String authorization) {
        AiInputPolicy.requireMessage(message);
        if(selected!=null && selected<1) throw ChatFailure.invalid();
        if(!BookingQuestions.personal(message,selected)) return new Help(false,"",null,List.of(),null,null);
        if(authorization==null || authorization.isBlank()) return answer("Bạn cần đăng nhập để tra cứu booking của chính mình. Sau khi đăng nhập, cho tôi mã booking hoặc tên tour đã đặt để kiểm tra hạn thanh toán.",null,List.of(),null,"P16");
        if(!authorization.matches("Bearer [A-Za-z0-9._~-]{20,16384}"))
            throw new ChatFailure(401,"LOGIN_REQUIRED");
        if(BookingQuestions.has(BookingQuestions.text(message),"cua nguoi khac","cua user khac","cua tai khoan khac"))
            return answer("Tôi chỉ có thể tra cứu booking của chính tài khoản đang đăng nhập; không truy cập đơn của người khác, kể cả từ tài khoản admin.",null,List.of(),null,"P16");
        Integer explicit=BookingQuestions.explicitId(message);
        if(explicit!=null && explicit<1) return answer("Mã booking chưa hợp lệ. Bạn kiểm tra lại mã trong Lịch sử đặt tour nhé.",null,List.of(),null,"P16");
        var page=reader.mine(authorization,explicit);
        var matches=page.bookings();
        String question=BookingQuestions.text(message);
        if(explicit==null) {
            var named=matches.stream().filter(b->mentions(question,b.tourProgram()) || mentions(question,b.tourTitle())).toList();
            if(!named.isEmpty()) matches=named;
            else {
                var partial=matches.stream().filter(b->titleMatches(question,b.tourTitle())).toList();
                if(!partial.isEmpty()) matches=partial;
                else if(namesSpecificTour(question)) {
                    return answer("Tôi chưa xác định được tour bạn nêu trong các booking vừa đọc. Bạn chọn đúng mã booking trong Lịch sử đặt tour hoặc danh sách dưới đây; tôi không dùng nhầm đơn của tour khác.",null,
                            matches.stream().map(b->new Choice(b.bookingId(),b.tourTitle(),b.departureAt(),b.state())).toList(),page.checkedAt(),"P16");
                } else if(selected!=null && isFollowup(question)) {
                    page=reader.mine(authorization,selected);matches=page.bookings();
                }
            }
        }
        if(matches.isEmpty()) return answer(explicit!=null?"Không tìm thấy booking này trong tài khoản của bạn. Bạn kiểm tra lại mã; tôi không tra cứu đơn của tài khoản khác.":"Chưa tìm thấy booking của bạn. Bạn có thể mở Lịch sử đặt tour để kiểm tra hoặc cung cấp mã booking cụ thể.",null,List.of(),page.checkedAt(),"P16");
        if(matches.size()!=1 || page.hasMore() && explicit==null) return answer("Hãy chọn đúng booking bên dưới hoặc nhập mã booking để kiểm tra hạn; tôi không tự chọn khi có nhiều đơn hoặc danh sách chưa đầy đủ."
                +(page.hasMore()?" Danh sách chỉ gồm "+BookingReader.MAX_PAGE_SIZE+" đơn gần nhất; đơn cũ hơn cần mã booking trong hồ sơ.":""),null,
                matches.stream().map(b->new Choice(b.bookingId(),b.tourTitle(),b.departureAt(),b.state())).toList(),page.checkedAt(),"P16");
        var b=matches.getFirst();
        String intro="Booking #"+b.bookingId()+" — "+b.tourTitle()+". "+(b.createdAt()==null?"":"Bạn đặt lúc "+date(b.createdAt())+". ")
                +(b.departureAt()==null?"":"Khởi hành lúc "+date(b.departureAt())+". ");
        String reply=switch(b.state()) {
            case "HELD","CHECKOUT" -> "Đơn chưa thanh toán. Hạn giữ chỗ/thanh toán đã lưu là "+date(b.paymentDeadline())
                    +" (giờ Việt Nam). Tại thời điểm kiểm tra "+date(b.checkedAt())+", bạn còn "+remaining(b.remainingSeconds())+". "
                    +(b.state().equals("CHECKOUT")?"Đây là đặt sát ngày: cần thanh toán ngay trong phiên tối đa 15 phút, không vượt giờ khởi hành. ":
                    "Theo P01, hạn là 08:00 ngày sớm hơn giữa ngày đặt + 6 ngày và ngày khởi hành − 6 ngày; không cộng thêm thời gian từ hôm nay. ")
                    +"Bạn thực hiện thanh toán tại đơn trên website; tôi chỉ tra cứu, không tự thanh toán hoặc gia hạn.";
            case "SCHEDULE_CHANGE_REQUIRED" -> "Chuyến cũ buộc phải dừng do sự cố. Hệ thống đang tạm dừng hết hạn giữ chỗ trên chuyến này; không thanh toán qua lịch cũ. Vào Hồ sơ → Lịch sử đặt chỗ → Dời lịch để xem đề xuất và phản hồi hoặc liên hệ shop. Chưa phản hồi không được coi là đồng ý; tôi không tự chuyển lịch.";
            case "REFUNDED" -> "Shop đã ghi nhận hoàn tiền thủ công toàn bộ và hủy booking. Vui lòng kiểm tra tài khoản nhận tiền và tham chiếu tại mục Dời lịch trong hồ sơ; hóa đơn thu tiền gốc được lưu làm lịch sử, không có nghĩa đơn còn hiệu lực.";
            case "PAID" -> "Hệ thống đã ghi nhận thanh toán khớp payment/hóa đơn. Bạn không cần thanh toán lại để giữ chỗ. Hạn cũ không còn là thời gian chờ trả tiền. Xem lịch sử đơn để kiểm tra chi tiết.";
            case "DEADLINE_PASSED" -> "Đơn chưa thanh toán đã qua hạn "+date(b.paymentDeadline())+". Không còn thời gian giữ chỗ hợp lệ; tác vụ hết hạn có thể chưa cập nhật trạng thái. Tôi không xác nhận chỗ đã trả kho và không tự gia hạn. Bạn kiểm tra lại trên website trước khi đặt mới.";
            case "EXPIRED" -> "Giữ chỗ đã hết hiệu lực. Bạn không thể thanh toán dựa trên booking cũ; nếu muốn đi tiếp, kiểm tra lịch/chỗ và đặt lại trên website.";
            case "CANCELLED" -> "Booking đã bị hủy/không còn hiệu lực. Không còn hạn thanh toán để giữ chỗ trên đơn này. Tôi không tự khôi phục hoặc đặt lại.";
            case "DEPARTURE_PASSED" -> "Giờ khởi hành đã qua. Tôi không xác nhận đơn chưa trả tiền còn giữ chỗ và không tạo hạn thanh toán mới.";
            case "LEGACY_UNVERIFIED" -> "Đây là booking cũ chưa có bản ghi hạn giữ chỗ để xác minh. Tôi không suy ra ngày hết hạn theo chính sách mới; cần kiểm tra với SGU Tour.";
            default -> "Dữ liệu thanh toán/giữ chỗ đang cần đối chiếu. Tôi chưa thể xác nhận đã trả tiền hay còn bao lâu; không thanh toán thêm chỉ dựa trên câu trả lời này. Liên hệ SGU Tour kèm mã booking để kiểm tra.";
        };
        String policyKey=switch(b.state()) {
            case "HELD","CHECKOUT","DEADLINE_PASSED" -> "P01";
            case "PAID","REVIEW_REQUIRED" -> "P06";
            case "SCHEDULE_CHANGE_REQUIRED" -> "P15";
            case "REFUNDED" -> "P11";
            case "EXPIRED","CANCELLED" -> "P02";
            default -> "P16";
        };
        return answer(intro+reply,b.bookingId(),List.of(),b.checkedAt(),policyKey);
    }
    private static boolean isFollowup(String question) {
        return BookingQuestions.has(question,"con bao lau","con bao nhieu","don nay","tour nay","han luc nao","het han chua","da thanh toan chua","da tra tien chua");
    }
    private static boolean mentions(String question,String value) {
        if(value==null || value.isBlank()) return false;
        String candidate=BookingQuestions.text(value);
        return !candidate.isBlank() && question.contains(candidate);
    }
    private static boolean namesSpecificTour(String question) {
        var match=Pattern.compile("\\btour\\s+(.+)").matcher(question);
        if(!match.find()) return false;
        String tail=match.group(1).split("\\s+(?:nhung|chua|thi|hien|vao|ngay|con|toi|minh)\\s+",2)[0].strip();
        return !tail.isEmpty() && !BookingQuestions.has(tail,"roi","da dat","nay","do","nao","cua toi","cua minh");
    }
    private static boolean titleMatches(String question,String title) {
        var words=Arrays.stream(BookingQuestions.text(title).split(" ")).filter(w->w.length()>2 && !List.of("tour","ngay","dem","trong","thanh","pho").contains(w)).distinct().toList();
        return words.size()>=2 && words.stream().filter(w->BookingQuestions.has(question,w)).count()>=Math.min(4,words.size());
    }
    private Help answer(String text,Integer selected,List<Choice> choices,OffsetDateTime checked,String key) {
        return new Help(true,text,selected,choices,checked,policies.info(key));
    }
    private static String date(OffsetDateTime date) { return date==null?"chưa xác định":FORMAT.format(date.atZoneSameInstant(ZONE)); }
    private static String remaining(long seconds) {
        if(seconds<60) return seconds+" giây";
        long minutes=seconds/60,days=minutes/1440,hours=minutes%1440/60,rest=minutes%60;
        return (days>0?days+" ngày ":"")+(hours>0?hours+" giờ ":"")+(rest>0?rest+" phút":"")
                +(seconds%60>0?" "+seconds%60+" giây":"");
    }
}
