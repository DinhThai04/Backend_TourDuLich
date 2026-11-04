package com.example.ai_service.account;

import com.example.ai_service.advisor.PolicyCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class BookingHelpServiceTest {
    final OffsetDateTime now=OffsetDateTime.parse("2026-11-04T08:00:00+07:00");
    final String token="Bearer dummy.jwt.for.owner.only.1234567890";
    final PolicyCatalog policies=new PolicyCatalog(new ObjectMapper(),PolicyCatalog.VERSION,PolicyCatalog.DEMO_SUPPORT_EMAIL);
    BookingReader.Booking booking(int id,String state) {
        return new BookingReader.Booking(id,3,"TOUR_03","Đà Nẵng — Cổng Trời Đông Giang",now.minusDays(3),now.plusDays(16),state,
                state,now.plusDays(3),state.equals("HELD")?259200:0,now,"reservation-v1");
    }
    BookingHelpService service(List<BookingReader.Booking> items,AtomicInteger reads) {
        return new BookingHelpService((authorization,id)->{reads.incrementAndGet();assertThat(authorization).isEqualTo(token);
            return new BookingReader.Page(items.stream().filter(b->id==null || b.bookingId()==id).toList(),false,now);},policies);
    }
    @Test void unrelatedTourSearchAndAnonymousBookingDoNotReadPrivateData() {
        var reads=new AtomicInteger();var service=service(List.of(booking(6,"HELD")),reads);
        assertThat(service.help("Tìm miền Trung, ngân sách 3 triệu",null,null).handled()).isFalse();
        assertThat(service.help("Tôi đã đặt tour rồi, còn mấy ngày thanh toán?",null,null).reply()).contains("đăng nhập");
        assertThat(reads).hasValue(0);
    }
    @ParameterizedTest @ValueSource(strings={"Tôi đã đặt tour abc nhưng chưa thanh toán, còn bao lâu?","Booking #6 còn hạn không?",
            "Mã đặt chỗ 6: tôi phải trả tiền khi nào?","Tôi chưa trả tiền tour đã đặt, kiểm tra giúp tôi", "Tôi đặt tour rồi, tình trạng đơn như thế nào?"})
    void variantsRouteLocally(String question) { assertThat(BookingQuestions.personal(question,null)).isTrue(); }
    @Test void computesAnswerFromPersistedDeadlineAndUsesVietnamTime() {
        var reads=new AtomicInteger();var result=service(List.of(booking(6,"HELD")),reads).help("Booking #6 còn bao lâu?",null,token);
        assertThat(result.reply()).contains("08:00 01/11/2026","08:00 07/11/2026","3 ngày","P01");
        assertThat(result.selectedBookingId()).isEqualTo(6);assertThat(reads).hasValue(1);
    }
    @Test void duplicateBookingsForSameTourRequireChoiceAndSelectedFollowupRereads() {
        var reads=new AtomicInteger();var service=service(List.of(booking(6,"HELD"),booking(7,"HELD")),reads);
        assertThat(service.help("Tôi đã đặt TOUR_03 nhưng chưa thanh toán",null,token).choices()).hasSize(2);
        assertThat(service.help("còn bao lâu?",6,token).selectedBookingId()).isEqualTo(6);
        assertThat(reads.get()).isGreaterThanOrEqualTo(2);
    }
    @Test void unknownOrOtherOwnerIdDoesNotFallBackToAnotherBooking() {
        var result=service(List.of(booking(6,"HELD")),new AtomicInteger()).help("Booking #99 còn bao lâu?",null,token);
        assertThat(result.selectedBookingId()).isNull();assertThat(result.reply()).contains("Không tìm thấy").doesNotContain("#6");
    }
    @Test void unmatchedNamedTourIsNotConfusedWithTheOnlyOwnedBooking() {
        var result=service(List.of(booking(6,"HELD")),new AtomicInteger()).help("Tôi đã đặt tour Phú Quốc nhưng chưa thanh toán",null,token);
        assertThat(result.selectedBookingId()).isNull();assertThat(result.choices()).hasSize(1);
        assertThat(result.reply()).contains("chưa xác định");
    }
    @ParameterizedTest @ValueSource(strings={"PAID","CANCELLED","EXPIRED","DEADLINE_PASSED","DEPARTURE_PASSED","LEGACY_UNVERIFIED","REVIEW_REQUIRED","CHECKOUT","SCHEDULE_CHANGE_REQUIRED","REFUNDED"})
    void neverPresentsNonHeldStatusesAsNewSixDayHolds(String state) {
        var result=service(List.of(booking(6,state)),new AtomicInteger()).help("Booking #6, tôi còn bao lâu?",null,token);
        if(!state.equals("CHECKOUT")) assertThat(result.reply()).doesNotContain("bạn còn 3 ngày");
        if(state.equals("LEGACY_UNVERIFIED")) assertThat(result.reply()).contains("không suy ra");
        if(state.equals("PAID")) assertThat(result.reply()).contains("không cần thanh toán lại");
    }
}
