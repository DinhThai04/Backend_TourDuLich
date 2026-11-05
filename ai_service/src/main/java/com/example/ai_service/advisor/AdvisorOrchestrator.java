package com.example.ai_service.advisor;

import com.example.ai_service.account.BookingQuestions;
import com.example.ai_service.catalog.TourFactsReader;
import com.example.ai_service.conversation.*;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;
import static com.example.ai_service.conversation.ChatModels.*;

@Component
public class AdvisorOrchestrator {
    private static final int SINGLE_TOUR=1;
    private static final int COMPARISON_TOURS=2;
    private static final Locale VIETNAMESE=Locale.forLanguageTag("vi-VN");
    private final TourFactsReader tours;
    private final PolicyCatalog policies;
    private final Clock clock;
    public AdvisorOrchestrator(TourFactsReader tours,PolicyCatalog policies,Clock clock) { this.tours=tours; this.policies=policies; this.clock=clock; }
    public Draft selection(State state,Plan plan) {
        var ids=TourReferences.resolve(state.latestPresentedTourIds(),plan.positions());
        int minimum=plan.intent()==Intent.COMPARE_TOURS?COMPARISON_TOURS:SINGLE_TOUR;
        if(ids.size()<minimum || plan.intent()==Intent.DETAIL_TOUR && ids.size()!=SINGLE_TOUR) return missing(state);
        // No arbitrary model ID or URL is accepted here. At most three bounded reader calls.
        var facts=new ArrayList<TourFactsReader.Facts>();
        for(int id:ids) { var current=tours.facts(id); if(current==null) return missing(state); facts.add(current); }
        if(plan.intent()==Intent.DETAIL_TOUR) {
            var detail=tours.detail(facts.getFirst());
            String reply="Lịch trình của tour “"+detail.title()+"” được đọc lại từ dữ liệu hiện tại của SGU Tour.";
            if(detail.itinerary().isEmpty()) reply+=" Tour chưa có lịch trình chi tiết trong dữ liệu hiện có.";
            if(detail.truncated()) reply+=" Nội dung dài đã được rút gọn; bạn mở trang chi tiết tour để đọc đầy đủ.";
            return draft(state,"ANSWER",reply,detail,null,null);
        }
        var items=facts.stream().map(t->new ComparisonItem(t.id(),t.title(),t.durationDays(),t.destinationName(),t.adultPriceVnd(),t.hasFutureDeparture())).toList();
        var comparison=new TourComparison(List.copyOf(ids),items,OffsetDateTime.now(clock));
        long lowest=facts.stream().mapToLong(TourFactsReader.Facts::adultPriceVnd).min().orElseThrow();
        long highest=facts.stream().mapToLong(TourFactsReader.Facts::adultPriceVnd).max().orElseThrow();
        String reply="Bảng so sánh dùng dữ liệu vừa kiểm tra của SGU Tour. Giá người lớn tham khảo thấp nhất trong các tour bạn chọn là "
                +String.format(VIETNAMESE,"%,d",lowest)+" VND; chênh lệch giữa mức cao nhất và thấp nhất là "
                +String.format(VIETNAMESE,"%,d",highest-lowest)+" VND. Đây không phải tổng nhóm. Có lịch tương lai không đồng nghĩa còn đủ chỗ cho nhóm của bạn.";
        return draft(state,"ANSWER",reply,null,comparison,null);
    }
    public Draft policy(State state,String message) {
        if(BookingQuestions.personal(message,null))
            return draft(state,"ANSWER","Để kiểm tra booking của chính bạn, hãy đăng nhập và dùng trợ lý trên website hoặc vào Lịch sử đặt tour. Tôi cần đọc hạn đã lưu của đúng đơn, không thể tính từ một ngày giả định.",null,null,policies.info("P16"));
        var policy=policies.answer(message);
        if(policy.isEmpty()) return draft(state,"OUT_OF_SCOPE","Bạn vui lòng tham khảo điều kiện đặt tour trên website hoặc liên hệ SGU Tour để được hướng dẫn chính sách. Tôi không truy cập hoặc thay đổi đơn đặt tour cá nhân.",null,null,null);
        return draft(state,"ANSWER",policy.get().text(),null,null,policy.get().info());
    }
    private Draft missing(State state) {
        var clarification=new Clarification("TOUR_SELECTION_REQUIRED",List.of("tourSelection"),
                "Bạn vui lòng chọn lại trong danh sách tour vừa hiển thị. Tour đã chọn có thể không còn trong dữ liệu hiện tại.",List.of());
        return new Draft(state.criteria(),Hint.TOUR_SELECTION_REQUIRED,state.latestPresentedTourIds(),state.page(),state.hasMore(),
                "CLARIFICATION",clarification.question(),List.of(),clarification,null,false);
    }
    private Draft draft(State state,String status,String reply,TourDetail detail,TourComparison comparison,PolicyInfo policy) {
        // No new card list is presented for these answers, so the latest search order/page remains intact.
        return new Draft(state.criteria(),state.pending(),state.latestPresentedTourIds(),state.page(),state.hasMore(),status,reply,
                List.of(),null,null,false,detail,comparison,policy);
    }
}
