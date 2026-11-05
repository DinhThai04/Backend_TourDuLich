package com.example.ai_service.advisor;

import com.example.ai_service.catalog.CatalogText;
import com.example.ai_service.conversation.ChatFailure;
import com.example.ai_service.conversation.ChatModels.BudgetScope;
import com.example.ai_service.conversation.ChatModels.Criteria;
import com.example.ai_service.conversation.ChatModels.Draft;
import com.example.ai_service.conversation.ChatModels.SearchMode;
import com.example.ai_service.conversation.ChatModels.SearchResult;
import com.example.ai_service.conversation.ChatModels.TourCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

@Component
public class GroundedResponseValidator {
    private static final Logger LOG=LoggerFactory.getLogger(GroundedResponseValidator.class);
    private final Clock clock;
    public GroundedResponseValidator(Clock clock) { this.clock=clock; }
    public SearchResult search(Criteria criteria,SearchResult result) {
        var clean=new ArrayList<TourCard>();
        if(result==null || result.tours()==null || result.searchSummary()==null) throw ChatFailure.catalog();
        for(var card:result.tours()) {
            if(criteria.region()!=null && card.region()!=criteria.region()) throw ChatFailure.catalog();
            var basis=criteria.budgetScope()==BudgetScope.TOTAL_GROUP?BudgetScope.TOTAL_GROUP:BudgetScope.PER_ADULT;
            if(card.price()==null || card.price().basis()!=basis || card.price().amountVnd()<0
                    || criteria.maxBudgetVnd()!=null && card.price().amountVnd()>criteria.maxBudgetVnd()) throw ChatFailure.catalog();
            if(criteria.searchMode()!=SearchMode.CATALOG) {
                var available=card.availability();
                if(available==null || !"AVAILABLE".equals(available.status()) || available.departureAt()==null
                        || !available.departureAt().toInstant().isAfter(clock.instant()) || available.availableSeats()==null)
                    throw ChatFailure.catalog();
                var day=available.departureAt().atZoneSameInstant(clock.getZone()).toLocalDate();
                if(criteria.departureFrom()!=null && day.isBefore(criteria.departureFrom())
                        || criteria.departureTo()!=null && day.isAfter(criteria.departureTo())) throw ChatFailure.catalog();
                var party=criteria.party();
                var counts=party==null?List.<Integer>of():Arrays.asList(party.adults(),party.children(),party.toddlers(),party.infants(),party.unclassifiedChildren());
                int required=counts.size()==5 && counts.stream().allMatch(Objects::nonNull)?counts.stream().mapToInt(Integer::intValue).sum():1;
                if(available.availableSeats()<Math.max(1,required)) throw ChatFailure.catalog();
            }
            var title=CatalogText.clean(card.title(),400); var destination=CatalogText.clean(card.destinationName(),120);
            if(title.text().isBlank() || title.truncated() || destination.text().isBlank() || destination.truncated()) throw ChatFailure.catalog();
            clean.add(new TourCard(card.tourId(),card.tourProgram(),title.text(),destination.text(),card.region(),card.durationDays(),
                    card.imageUrl(),card.detailPath(),card.price(),card.availability()));
        }
        return new SearchResult(List.copyOf(clean),result.searchSummary(),result.page(),result.size());
    }
    public Draft answer(Draft draft) {
        int payloads=(draft.detail()==null?0:1)+(draft.comparison()==null?0:1)+(draft.policy()==null?0:1);
        boolean answer="ANSWER".equals(draft.status());
        if(answer!=(payloads==1)) {
            LOG.error("ai_draft_invalid status={} payloads={}",draft.status(),payloads);
            throw new ChatFailure(500,"AI_INTERNAL_ERROR");
        }
        return draft;
    }
}
