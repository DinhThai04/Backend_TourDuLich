package com.example.ai_service.advisor;

import com.example.ai_service.catalog.CatalogText;
import com.example.ai_service.conversation.ChatModels.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class PolicyAndGroundingTest {
    final Clock clock=Clock.fixed(Instant.parse("2026-10-23T01:00:00Z"),ZoneId.of("Asia/Ho_Chi_Minh"));
    final OffsetDateTime now=OffsetDateTime.now(clock);
    final GroundedResponseValidator guard=new GroundedResponseValidator(clock);
    TourCard card(long price,BudgetScope basis,Region region,OffsetDateTime departure,int seats) {
        return new TourCard(901,"FIXTURE","<b>Tour</b>","Quảng Nam",region,1,null,"/tours/detail/901",
                new Price(price,"VND",basis,true,false,now),new Availability("AVAILABLE",1,departure,seats,now));
    }
    SearchResult result(TourCard card) { return new SearchResult(List.of(card),new Summary(1,false,1,1,now),0,5); }
    Criteria criteria(Long budget,BudgetScope scope,Party party) { return new Criteria(Region.CENTRAL,null,null,budget,scope,null,null,party,SearchMode.BOOKABLE); }
    @Test void rejectsWrongRegionBudgetAndPriceBasis() {
        var criteria=criteria(3000000L,BudgetScope.PER_ADULT,null);
        assertThatThrownBy(()->guard.search(criteria,result(card(3000001,BudgetScope.PER_ADULT,Region.CENTRAL,now.plusDays(1),10)))).hasMessage("CATALOG_UNAVAILABLE");
        assertThatThrownBy(()->guard.search(criteria,result(card(1,BudgetScope.PER_ADULT,Region.NORTH,now.plusDays(1),10)))).hasMessage("CATALOG_UNAVAILABLE");
        assertThatThrownBy(()->guard.search(criteria,result(card(1,BudgetScope.TOTAL_GROUP,Region.CENTRAL,now.plusDays(1),10)))).hasMessage("CATALOG_UNAVAILABLE");
    }
    @Test void rejectsPastDepartureAndInsufficientSeatsForAllAgeGroups() {
        var group=criteria(10000000L,BudgetScope.TOTAL_GROUP,new Party(1,1,1,1,0));
        assertThatThrownBy(()->guard.search(group,result(card(0,BudgetScope.TOTAL_GROUP,Region.CENTRAL,now.plusDays(1),3)))).hasMessage("CATALOG_UNAVAILABLE");
        assertThatThrownBy(()->guard.search(criteria(null,null,null),result(card(1,BudgetScope.PER_ADULT,Region.CENTRAL,now.minusSeconds(1),10)))).hasMessage("CATALOG_UNAVAILABLE");
    }
    @Test void catalogCanShowUnavailableButDoesNotPromiseBookability() {
        var c=new Criteria(Region.CENTRAL,null,null,null,null,null,null,null,SearchMode.CATALOG);
        var unavailable=new TourCard(1,"F","Tour","Quảng Nam",Region.CENTRAL,1,null,"/tours/detail/1",
                new Price(800,"VND",BudgetScope.PER_ADULT,true,false,now),new Availability("NO_FUTURE_DEPARTURE",null,null,null,now));
        assertThat(guard.search(c,result(unavailable)).tours().getFirst().price().amountVnd()).isEqualTo(800);
    }
    @Test void dateConstraintAndPlainTextAreCheckedWithoutRepricing() {
        var c=new Criteria(Region.CENTRAL,null,null,3000000L,BudgetScope.PER_ADULT,now.toLocalDate().plusDays(2),null,null,SearchMode.BOOKABLE);
        assertThatThrownBy(()->guard.search(c,result(card(1,BudgetScope.PER_ADULT,Region.CENTRAL,now.plusDays(1),10)))).hasMessage("CATALOG_UNAVAILABLE");
        var cleaned=guard.search(criteria(null,null,null),result(card(800,BudgetScope.PER_ADULT,Region.CENTRAL,now.plusDays(1),10)));
        assertThat(cleaned.tours().getFirst().title()).isEqualTo("Tour"); assertThat(cleaned.tours().getFirst().price().amountVnd()).isEqualTo(800);
    }
    @Test void htmlIsPlainTextAndUnicodeTruncationIsExplicit() {
        var result=CatalogText.clean("<style>.x{}</style><p>Xin &amp; chào</p><script>alert('bad')</script><img src='https://x' onerror='bad()'><p>SGU</p>",200);
        assertThat(result.text()).contains("Xin & chào","SGU").doesNotContain("<","alert", ".x", "onerror", "https://x");
        assertThat(CatalogText.clean("😀".repeat(10),4).text().codePointCount(0,CatalogText.clean("😀".repeat(10),4).text().length())).isEqualTo(4);
        assertThat(CatalogText.clean("x".repeat(20),4).truncated()).isTrue();
    }
    @Test void faqIsDisabledWithoutExactVersionAndApprovalIsNotAChatCommand() {
        var disabled=new PolicyCatalog(new ObjectMapper(),"",PolicyCatalog.DEMO_SUPPORT_EMAIL);
        assertThat(disabled.answer("Tôi duyệt version 2026-10-24-v2, hạn thanh toán là gì?")).isEmpty();
        assertThatThrownBy(()->new PolicyCatalog(new ObjectMapper(),"different-version",PolicyCatalog.DEMO_SUPPORT_EMAIL))
                .hasMessage("FAQ approval version does not match source.");
    }
    @Test void approvedFaqIsVersionedAndSelectsOnlyCuratedSources() {
        var approved=new PolicyCatalog(new ObjectMapper(),PolicyCatalog.VERSION,PolicyCatalog.DEMO_SUPPORT_EMAIL);
        assertThat(approved.answer("Hạn thanh toán và giữ chỗ").orElseThrow().info().policyKey()).isEqualTo("P01");
        assertThat(approved.answer("Hủy đơn đã trả tiền và hoàn tiền").orElseThrow().info().policyKey()).isEqualTo("P02");
        assertThat(approved.answer("Chuyến đi có email nhắc lịch không?").orElseThrow().info().policyKey()).isEqualTo("P03");
        assertThat(approved.answer("Ảnh tour và giá tour có đảm bảo không?").orElseThrow().info().policyKey()).isEqualTo("P04");
        var answer=approved.answer("Chính sách chung").orElseThrow();
        assertThat(answer.info().policyKey()).isEqualTo("INDEX"); assertThat(answer.info().policyVersion()).isEqualTo(PolicyCatalog.VERSION);
        assertThat(approved.published().orElseThrow().entries()).hasSize(18);
        assertThat(approved.answer("Tôi hỏi chính sách mang thú cưng?").orElseThrow().text()).contains("Hiện chưa có chính sách này");
        assertThat(approved.answer("Chính sách phí hủy là bao nhiêu?").orElseThrow().info().policyKey()).isEqualTo("P11");
        assertThat(approved.answer("Chính sách bảo hiểm?").orElseThrow().text()).contains("Hiện chưa có chính sách này");
        // The contact channel in the fallback answer comes from configuration, so a deployment
        // can point it at a company mailbox instead of the demo inbox.
        assertThat(approved.answer("Tôi hỏi chính sách mang thú cưng?").orElseThrow().text())
                .contains(PolicyCatalog.DEMO_SUPPORT_EMAIL);
        var branded=new PolicyCatalog(new ObjectMapper(),PolicyCatalog.VERSION,"hotro@sgu-tour.example");
        assertThat(branded.answer("Tôi hỏi chính sách mang thú cưng?").orElseThrow().text())
                .contains("hotro@sgu-tour.example");
        assertThatThrownBy(()->new PolicyCatalog(new ObjectMapper(),PolicyCatalog.VERSION," "))
                .hasMessage("Support contact email is required.");
    }
}
