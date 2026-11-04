package com.example.ai_service.conversation;

import com.example.ai_service.model.ModelFailure;
import com.example.ai_service.security.AiInputPolicy;
import org.springframework.stereotype.Component;
import java.text.Normalizer;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static com.example.ai_service.conversation.ChatModels.*;

@Component
public class CriteriaMerger {
    public record Merged(Criteria criteria,Hint hint,boolean changed) {}
    public Merged merge(Criteria previous,Plan plan,Supplier<List<Location>> places,LocalDate today) {
        if(plan==null || plan.intent()==null || plan.reset()==null || plan.changes()==null || plan.positions()==null || plan.clarification()==null)
            throw invalid();
        if((plan.reset() || !plan.changes().isEmpty()) && plan.intent()!=Intent.SEARCH_TOURS && plan.intent()!=Intent.CLARIFY) throw invalid();
        if(plan.changes().size()>20 || plan.positions().size()>3) throw invalid();
        Criteria base=plan.reset()?Criteria.empty():previous;
        var changes=new EnumMap<Field,Change>(Field.class);
        for(var change:plan.changes()) {
            if(change==null || change.field()==null || change.action()==null || change.value()==null || change.value().length()>120
                    || changes.put(change.field(),change)!=null) throw invalid();
            if(change.action()==Action.CLEAR && !change.value().isEmpty()) throw invalid();
            if(change.action()==Action.SET && (change.field()==Field.DESTINATION || change.field()==Field.ORIGIN)
                    && !AiInputPolicy.locationName(change.value())) throw invalid();
        }
        if(plan.clarification()==Hint.BUDGET_UNIT_REQUIRED && changes.containsKey(Field.MAX_BUDGET_VND)) throw invalid();
        try {
            Region region=enumeration(changes,Field.REGION,base.region(),Region.class);
            BudgetScope scope=enumeration(changes,Field.BUDGET_SCOPE,base.budgetScope(),BudgetScope.class);
            SearchMode mode=enumeration(changes,Field.SEARCH_MODE,base.searchMode(),SearchMode.class);
            if(mode==null) mode=SearchMode.BOOKABLE;
            Long amount=number(changes,Field.MAX_BUDGET_VND,base.maxBudgetVnd(),1_000_000_000_000L);
            if(isClear(changes,Field.MAX_BUDGET_VND)) scope=null;
            LocalDate from=date(changes,Field.DEPARTURE_FROM,base.departureFrom());
            LocalDate to=date(changes,Field.DEPARTURE_TO,base.departureTo());
            if(from!=null && to!=null && from.isAfter(to)) throw invalid();
            var old=base.party()==null?new Party(null,null,null,null,null):base.party();
            Party party=new Party(count(changes,Field.ADULTS,old.adults()),count(changes,Field.CHILDREN,old.children()),
                    count(changes,Field.TODDLERS,old.toddlers()),count(changes,Field.INFANTS,old.infants()),
                    count(changes,Field.UNCLASSIFIED_CHILDREN,old.unclassifiedChildren()));
            var counts=Arrays.asList(party.adults(),party.children(),party.toddlers(),party.infants(),party.unclassifiedChildren());
            long total=counts.stream().filter(Objects::nonNull).mapToLong(Integer::longValue).sum();
            if(total>100 || (counts.stream().allMatch(Objects::nonNull) && total==0)) throw invalid();
            if(counts.stream().allMatch(Objects::isNull)) party=null;
            Integer destination=base.destinationId(),origin=base.originId();
            Hint hint=plan.clarification();
            // One lazy lookup per turn, only when geography actually requires it.
            var directory=new LazyPlaces(places);
            if(isClear(changes,Field.REGION)) destination=null;
            if(changes.containsKey(Field.DESTINATION)) {
                var change=changes.get(Field.DESTINATION);
                if(change.action()==Action.CLEAR) destination=null;
                else {
                    var place=directory.find(change.value(),"DESTINATION");
                    if(place==null) { hint=Hint.UNKNOWN_DESTINATION; destination=null; }
                    else if(changes.containsKey(Field.REGION) && region!=null && region!=place.region()) {
                        hint=Hint.AMBIGUOUS_REQUEST; region=base.region(); destination=base.destinationId();
                    } else { destination=place.canonicalId(); region=place.region(); }
                }
            } else if(changes.containsKey(Field.REGION) && destination!=null && region!=null) {
                var place=directory.byId(destination,"DESTINATION");
                if(place.region()!=region) destination=null;
            }
            if(changes.containsKey(Field.ORIGIN)) {
                var change=changes.get(Field.ORIGIN);
                if(change.action()==Action.CLEAR) origin=null;
                else { var place=directory.find(change.value(),"DEPARTURE");
                    if(place==null) { hint=Hint.UNKNOWN_ORIGIN; origin=null; } else origin=place.canonicalId(); }
            }
            var next=new Criteria(region,destination,origin,amount,scope,from,to,party,mode);
            if(hint==Hint.NONE && to!=null && to.isBefore(today)) hint=Hint.DATE_REQUIRED;
            if(hint==Hint.NONE && amount!=null && scope==null) hint=Hint.BUDGET_SCOPE_REQUIRED;
            if(hint==Hint.NONE && scope==BudgetScope.TOTAL_GROUP && !complete(party)) hint=Hint.PASSENGER_DETAILS_REQUIRED;
            return new Merged(next,hint,plan.reset() || !next.equals(previous));
        } catch(ModelFailure known) { throw known; }
        catch(ChatFailure upstream) { throw upstream; }
        catch(RuntimeException badPatch) { throw invalid(); }
    }
    static boolean complete(Party p) { return p!=null && p.adults()!=null && p.children()!=null && p.toddlers()!=null
            && p.infants()!=null && p.unclassifiedChildren()!=null && p.unclassifiedChildren()==0; }
    private boolean isClear(Map<Field,Change> changes,Field field) { return changes.containsKey(field) && changes.get(field).action()==Action.CLEAR; }
    private <T extends Enum<T>> T enumeration(Map<Field,Change> changes,Field field,T current,Class<T> type) {
        var change=changes.get(field); return change==null?current:change.action()==Action.CLEAR?null:Enum.valueOf(type,change.value());
    }
    private Long number(Map<Field,Change> changes,Field field,Long current,long cap) {
        var change=changes.get(field); if(change==null) return current; if(change.action()==Action.CLEAR) return null;
        if(!change.value().matches("0|[1-9][0-9]{0,12}")) throw invalid();
        long value=Long.parseLong(change.value()); if(value>cap) throw invalid(); return value;
    }
    private Integer count(Map<Field,Change> changes,Field field,Integer current) {
        var value=number(changes,field,current==null?null:current.longValue(),100); return value==null?null:value.intValue();
    }
    private LocalDate date(Map<Field,Change> changes,Field field,LocalDate current) {
        var change=changes.get(field); return change==null?current:change.action()==Action.CLEAR?null:LocalDate.parse(change.value());
    }
    private static ModelFailure invalid() { return new ModelFailure(ModelFailure.Type.AI_INVALID_OUTPUT); }
    private static String key(String name) {
        return Normalizer.normalize(name,Normalizer.Form.NFD).replaceAll("\\p{M}","").replace('đ','d').replace('Đ','D')
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]","");
    }
    private static final class LazyPlaces {
        final Supplier<List<Location>> source; List<Location> values;
        LazyPlaces(Supplier<List<Location>> source) { this.source=source; }
        List<Location> values() { if(values==null) values=source.get(); return values; }
        Location find(String name,String type) {
            var found=values().stream().filter(p->type.equals(p.type()) && key(name).equals(key(p.name()))
                    && (type.equals("DEPARTURE") || (p.adviceSupported() && p.region()!=null))).toList();
            if(found.isEmpty()) return null;
            if(found.stream().map(Location::canonicalId).distinct().count()!=1) return null;
            return found.getFirst();
        }
        Location byId(int id,String type) {
            return values().stream().filter(p->p.id()==id && type.equals(p.type())).findFirst().orElseThrow(ChatFailure::catalog);
        }
    }
}
