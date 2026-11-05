package com.example.tour_service.advice;

import com.example.tour_service.dto.request.TourPriceBatchRequest;
import com.example.tour_service.dto.response.TourPriceResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import static com.example.tour_service.advice.AdviceModels.*;

@Service
public class AdviceService {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AdviceService.class);
    private static final BigDecimal MAX_BUDGET = new BigDecimal("1000000000000");
    private final AdviceCatalogRepository repository;
    private final AdvicePricingClient prices;
    private final AdviceMediaClient media;
    private final AdviceLocations locations;
    private final AdviceConfiguration.Limits limits;
    private final Clock clock;
    public AdviceService(AdviceCatalogRepository repository, AdvicePricingClient prices, AdviceMediaClient media,
                         AdviceLocations locations, AdviceConfiguration.Limits limits, @Qualifier("adviceClock") Clock clock) {
        this.repository=repository; this.prices=prices; this.media=media; this.locations=locations; this.limits=limits; this.clock=clock;
    }
    public List<LocationOption> locations() {
        try { return locations.options(repository.places()); }
        catch (RuntimeException failure) { LOG.error("advice_locations_failed", failure); throw AdviceException.unavailable(); }
    }
    public SearchResult search(SearchRequest request) {
        validate(request);
        try { return searchValidated(request); }
        catch (AdviceException failure) { throw failure; }
        catch (RuntimeException failure) { LOG.error("advice_search_failed", failure); throw AdviceException.unavailable(); }
    }
    private void validate(SearchRequest request) {
        if (request==null || request.criteria()==null) throw AdviceException.invalid("Cần có criteria.");
        if (request.page()!=null && request.page()<0) throw AdviceException.invalid("page phải không âm.");
        if (request.size()!=null && (request.size()<1 || request.size()>5)) throw AdviceException.invalid("size phải từ 1 đến 5.");
        var c=request.criteria();
        if ((c.destinationId()!=null && c.destinationId()<=0) || (c.originId()!=null && c.originId()<=0))
            throw AdviceException.invalid("ID địa điểm không hợp lệ.");
        if (c.departureFrom()!=null && c.departureTo()!=null && c.departureFrom().isAfter(c.departureTo()))
            throw AdviceException.invalid("Khoảng ngày khởi hành không hợp lệ.");
        if (c.maxBudgetVnd()!=null) {
            if (c.maxBudgetVnd().signum()<0 || c.maxBudgetVnd().stripTrailingZeros().scale()>0 || c.maxBudgetVnd().compareTo(MAX_BUDGET)>0)
                throw AdviceException.invalid("Ngân sách phải là số nguyên VND từ 0 đến 1.000.000.000.000.");
            if (c.budgetScope()==null) throw AdviceException.invalid("Cần làm rõ ngân sách cho người lớn hay tổng nhóm.");
        }
        var party=c.party();
        if (party!=null) {
            long known=0;
            for (Integer count : counts(party)) {
                if (count!=null && (count<0 || count>limits.maxPartySize())) throw AdviceException.invalid("Số hành khách ngoài giới hạn yêu cầu.");
                if (count!=null) known+=count;
            }
            if (known>limits.maxPartySize() || (completeHeadcount(party) && known==0))
                throw AdviceException.invalid("Số hành khách ngoài giới hạn yêu cầu.");
        }
        if (c.budgetScope()==BudgetScope.TOTAL_GROUP && (party==null || !completeHeadcount(party) || party.unclassifiedChildren()!=0))
            throw AdviceException.invalid("Cần đủ số người và nhóm tuổi để tính tổng nhóm.");
    }
    private List<Integer> counts(Party p) { return Arrays.asList(p.adults(),p.children(),p.toddlers(),p.infants(),p.unclassifiedChildren()); }
    private boolean completeHeadcount(Party p) { return counts(p).stream().allMatch(Objects::nonNull); }
    private Integer requiredSeats(Party p) { return p!=null && completeHeadcount(p) ? counts(p).stream().mapToInt(Integer::intValue).sum() : null; }

    private SearchResult searchValidated(SearchRequest request) {
        var c=request.criteria(); int page=request.page()==null?0:request.page(); int size=request.size()==null?5:request.size();
        OffsetDateTime checkedAt=OffsetDateTime.now(clock); LocalDateTime now=checkedAt.toLocalDateTime();
        var places=repository.places();
        var byId=places.stream().collect(Collectors.toMap(AdviceCatalogRepository.Place::id,p->p));
        Set<Integer> destinationIds=resolve(c.destinationId(),"DESTINATION",places,byId);
        Set<Integer> originIds=resolve(c.originId(),"DEPARTURE",places,byId);
        if (c.destinationId()!=null) {
            Region region=locations.region(byId.get(c.destinationId()).name());
            if (region==null) throw AdviceException.invalid("Điểm đến chưa được cấu hình vùng tư vấn.");
            if (c.region()!=null && c.region()!=region) throw AdviceException.invalid("Vùng và điểm đến đang mâu thuẫn.");
        }
        List<AdviceCatalogRepository.Candidate> candidates=new ArrayList<>();
        for (var t:repository.candidates()) {
            var destination=byId.get(t.destinationId()); var origin=byId.get(t.originId());
            if (destination==null || origin==null || !"DESTINATION".equals(destination.type()) || !"DEPARTURE".equals(origin.type()))
                throw AdviceException.unavailable();
            if (!destinationIds.isEmpty() && !destinationIds.contains(t.destinationId())) continue;
            if (!originIds.isEmpty() && !originIds.contains(t.originId())) continue;
            Region region=locations.region(destination.name());
            // An unmapped destination must not silently disappear from a region-wide search.
            if (c.region()!=null && region==null) throw AdviceException.unavailable();
            if (c.region()!=null && c.region()!=region) continue;
            if (t.id()<=0 || t.priceId()==null || t.priceId()<=0 || t.duration()<=0 || t.title()==null || t.title().isBlank())
                throw AdviceException.unavailable();
            candidates.add(t);
        }
        if (candidates.isEmpty()) return result(List.of(),0,0,page,size,checkedAt);
        var priceIds=candidates.stream().map(AdviceCatalogRepository.Candidate::priceId).distinct().toList();
        var priceList=com.example.tour_service.service.PriceBatchLoader.load(priceIds,prices::prices);
        Map<Long,TourPriceResponse> priceMap=new HashMap<>();
        for (var p:priceList) {
            if (p==null || p.getId()==null || !priceIds.contains(p.getId()) || priceMap.put(p.getId(),p)!=null)
                throw AdviceException.unavailable();
        }
        if (priceMap.size()!=priceIds.size()) throw AdviceException.unavailable();
        List<TourCard> quoted=new ArrayList<>();
        for (var t:candidates) {
            long amount=quote(priceMap.get(t.priceId()),c);
            if (c.maxBudgetVnd()!=null && BigDecimal.valueOf(amount).compareTo(c.maxBudgetVnd())>0) continue;
            quoted.add(new TourCard(t.id(),t.program(),t.title(),byId.get(t.destinationId()).name(),
                    locations.region(byId.get(t.destinationId()).name()),t.duration(),null,"/tours/detail/"+t.id(),
                    new Price(amount,"VND",c.budgetScope()==BudgetScope.TOTAL_GROUP?BudgetScope.TOTAL_GROUP:BudgetScope.PER_ADULT,
                            true,false,checkedAt),null));
        }
        if (quoted.isEmpty()) return result(List.of(),0,0,page,size,checkedAt);
        var departures=repository.futureDepartures(quoted.stream().map(TourCard::tourId).toList(),now);
        for (var d:departures) if (d.at()==null || d.returnsAt()==null || d.returnsAt().isBefore(d.at()) || d.seats()==null || d.seats()<0)
            throw AdviceException.unavailable();
        var grouped=departures.stream().collect(Collectors.groupingBy(AdviceCatalogRepository.Departure::tourId));
        List<TourCard> cards=new ArrayList<>(); int bookable=0;
        for (var t:quoted) {
            var available=availability(grouped.getOrDefault(t.tourId(),List.of()),c,checkedAt);
            if (available.status()==AvailabilityStatus.AVAILABLE) bookable++;
            if (c.searchMode()==SearchMode.CATALOG || available.status()==AvailabilityStatus.AVAILABLE)
                cards.add(new TourCard(t.tourId(),t.tourProgram(),t.title(),t.destinationName(),t.region(),t.durationDays(),
                        null,t.detailPath(),t.price(),available));
        }
        cards.sort(Comparator.comparingLong((TourCard t)->t.price().amountVnd())
                .thenComparing(t->t.availability().departureAt(),Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparingInt(TourCard::tourId));
        return result(cards,quoted.size(),bookable,page,size,checkedAt);
    }
    private Set<Integer> resolve(Integer id, String type, List<AdviceCatalogRepository.Place> places,
                                  Map<Integer,AdviceCatalogRepository.Place> byId) {
        if (id==null) return Set.of();
        var place=byId.get(id);
        if (place==null || !type.equals(place.type())) throw AdviceException.invalid("Địa điểm không tồn tại hoặc không đúng loại.");
        return locations.aliases(place,places);
    }
    private long money(BigDecimal value) {
        if (value==null || value.signum()<0) throw AdviceException.unavailable();
        try { return value.longValueExact(); } catch (ArithmeticException invalid) { throw AdviceException.unavailable(); }
    }
    private long quote(TourPriceResponse price, Criteria c) {
        long adult=money(price.getAdultPrice()), child=money(price.getChildPrice()),
                toddler=money(price.getToddlerPrice()), infant=money(price.getInfantPrice());
        if (c.budgetScope()!=BudgetScope.TOTAL_GROUP) return adult;
        Party p=c.party();
        // Matches current VNPay passenger fares: there is no persisted single-room selection.
        return BigDecimal.valueOf(adult).multiply(BigDecimal.valueOf(p.adults()))
                .add(BigDecimal.valueOf(child).multiply(BigDecimal.valueOf(p.children())))
                .add(BigDecimal.valueOf(toddler).multiply(BigDecimal.valueOf(p.toddlers())))
                .add(BigDecimal.valueOf(infant).multiply(BigDecimal.valueOf(p.infants()))).longValueExact();
    }
    private Availability availability(List<AdviceCatalogRepository.Departure> future, Criteria c, OffsetDateTime checkedAt) {
        if (future.isEmpty()) return new Availability(AvailabilityStatus.NO_FUTURE_DEPARTURE,null,null,null,checkedAt);
        var matching=future.stream().filter(d -> (c.departureFrom()==null || !d.at().toLocalDate().isBefore(c.departureFrom()))
                && (c.departureTo()==null || !d.at().toLocalDate().isAfter(c.departureTo())))
                .sorted(Comparator.comparing(AdviceCatalogRepository.Departure::at).thenComparingInt(AdviceCatalogRepository.Departure::id)).toList();
        if (matching.isEmpty()) return new Availability(AvailabilityStatus.NO_MATCHING_DEPARTURE,null,null,null,checkedAt);
        Integer seats=requiredSeats(c.party()); int required=seats==null?1:seats;
        var selected=matching.stream().filter(d->d.seats()>=required).findFirst();
        var d=selected.orElse(matching.getFirst());
        return new Availability(selected.isPresent()?AvailabilityStatus.AVAILABLE:AvailabilityStatus.INSUFFICIENT_SEATS,
                d.id(),d.at().atZone(clock.getZone()).toOffsetDateTime(),d.seats(),checkedAt);
    }
    private SearchResult result(List<TourCard> all, int catalogCount, int bookable, int page, int size, OffsetDateTime at) {
        int from=(int)Math.min((long)page*size,all.size()), to=Math.min(from+size,all.size());
        var selected=all.subList(from,to); Map<Integer,String> images=images(selected);
        var cards=selected.stream().map(t->t.withImage(images.get(t.tourId()))).toList();
        return new SearchResult(cards,new Summary(cards.size(),to<all.size(),catalogCount,bookable,at),page,size);
    }
    private Map<Integer,String> images(List<TourCard> cards) {
        Map<Integer,String> found=new HashMap<>();
        if (cards.isEmpty()) return found;
        Set<String> ids=cards.stream().map(t->String.valueOf(t.tourId())).collect(Collectors.toSet());
        try {
            var rows=media.media(ids.stream().sorted().toList());
            if (rows!=null) for (var row:rows) {
                if (row!=null && ids.contains(row.getTourId()) && safeImage(row.getUrl()))
                    found.putIfAbsent(Integer.parseInt(row.getTourId()),row.getUrl());
            }
        } catch (RuntimeException ignored) { /* Optional media never turns valid catalog results into an outage. */ }
        return found;
    }
    static boolean safeImage(String url) {
        if (url==null || url.length()>2048) return false;
        try {
            URI uri=URI.create(url);
            return "https".equalsIgnoreCase(uri.getScheme()) && "res.cloudinary.com".equalsIgnoreCase(uri.getHost())
                    && uri.getUserInfo()==null && uri.getFragment()==null && (uri.getPort()==-1 || uri.getPort()==443)
                    && uri.getPath()!=null && uri.getPath().matches("/[A-Za-z0-9_-]+/image/upload/.+");
        } catch (IllegalArgumentException invalid) { return false; }
    }
}
