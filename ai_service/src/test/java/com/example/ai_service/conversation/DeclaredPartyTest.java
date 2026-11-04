package com.example.ai_service.conversation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.time.LocalDate;
import java.util.*;
import static com.example.ai_service.conversation.ChatModels.*;
import static org.assertj.core.api.Assertions.assertThat;

class DeclaredPartyTest {
    Plan wrong(){return new Plan(Intent.SEARCH_TOURS,false,List.of(new Change(Field.ADULTS,Action.SET,"2"),new Change(Field.CHILDREN,Action.SET,"2"),new Change(Field.TODDLERS,Action.SET,"0"),new Change(Field.INFANTS,Action.SET,"1"),new Change(Field.UNCLASSIFIED_CHILDREN,Action.SET,"0")),List.of(),Hint.NONE);}
    Criteria base(){return new Criteria(Region.CENTRAL,null,null,10000000L,BudgetScope.TOTAL_GROUP,LocalDate.of(2026,11,1),LocalDate.of(2026,11,30),null,SearchMode.BOOKABLE);}
    CriteriaMerger.Merged merge(Plan plan){return new CriteriaMerger().merge(base(),plan,List::of,LocalDate.of(2026,10,24));}
    @Test void correctsObservedWrongModelClassificationWithoutChangingOtherCriteria(){
        var plan=DeclaredParty.reconcile(wrong(),"Đổi ngân sách. Nhóm chỉ có hai người lớn, một bé bảy tuổi, một bé ba tuổi và một bé một tuổi, tổng năm người. Giữ miền Trung.");
        var result=merge(plan);assertThat(result.hint()).isEqualTo(Hint.NONE);
        assertThat(result.criteria().party()).isEqualTo(new Party(2,1,1,1,0));
        assertThat(result.criteria().region()).isEqualTo(Region.CENTRAL);assertThat(result.criteria().departureFrom()).isEqualTo(base().departureFrom());assertThat(result.criteria().maxBudgetVnd()).isEqualTo(10000000L);
    }
    @ParameterizedTest @CsvSource({"1,2,0,0,1","2,2,0,1,0","4,2,0,1,0","5,2,1,0,0","11,2,1,0,0","12,3,0,0,0"})
    void ageBoundariesAreClassifiedByBackend(int age,int adults,int children,int toddlers,int infants){
        var plan=DeclaredParty.reconcile(wrong(),"Nhóm tôi chỉ có 2 người lớn và 1 bé "+age+" tuổi, tổng 3 người.");
        assertThat(merge(plan).criteria().party()).isEqualTo(new Party(adults,children,toddlers,infants,0));
    }
    @Test void supportsWrittenNumbersAndMultipleChildrenSameAge(){
        assertThat(merge(DeclaredParty.reconcile(wrong(),"Chỉ có hai người lớn và hai bé ba tuổi, tổng bốn người.")).criteria().party()).isEqualTo(new Party(2,0,2,0,0));
        assertThat(merge(DeclaredParty.reconcile(wrong(),"Chỉ có một người lớn và một bé mười một tuổi, tổng hai người.")).criteria().party()).isEqualTo(new Party(1,1,0,0,0));
        assertThat(merge(DeclaredParty.reconcile(wrong(),"Chỉ có hai mươi người lớn, tổng hai mươi người.")).criteria().party()).isEqualTo(new Party(20,0,0,0,0));
    }
    @Test void incompleteAgesOrInconsistentTotalAskInsteadOfQuoting(){
        for(String text:List.of("Nhóm chỉ có 2 người lớn và một bé chưa rõ tuổi, tổng 3 người.","Nhóm chỉ có 2 người lớn và 1 bé 7 tuổi, tổng 4 người.","Chỉ có 2 người lớn và một bé ba tuổi nhưng chưa biết còn ai, tổng 3 người.")){
            var plan=DeclaredParty.reconcile(wrong(),text);assertThat(plan.intent()).isEqualTo(Intent.CLARIFY);assertThat(merge(plan).hint()).isEqualTo(Hint.PASSENGER_DETAILS_REQUIRED);
        }
    }
    @Test void openEndedOrIncrementalMessagesStillUseNormalPlan(){
        var plan=wrong();assertThat(DeclaredParty.reconcile(plan,"Thêm một bé 3 tuổi")).isSameAs(plan);assertThat(DeclaredParty.reconcile(plan,"Hai người lớn, một bé 7 tuổi")).isSameAs(plan);
    }
    @Test void privateIntentIsNeverChangedByPassengerWords(){
        var plan=new Plan(Intent.OUT_OF_SCOPE,false,List.of(),List.of(),Hint.NONE);
        assertThat(DeclaredParty.reconcile(plan,"Hủy đơn cho nhóm chỉ có 2 người lớn và 1 bé 3 tuổi, tổng 3 người.")).isSameAs(plan);
    }
}
