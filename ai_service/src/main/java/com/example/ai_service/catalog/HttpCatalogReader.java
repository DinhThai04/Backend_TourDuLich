package com.example.ai_service.catalog;

import com.example.ai_service.conversation.ChatFailure;
import com.example.ai_service.conversation.ChatModels.*;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;
import java.net.URI;
import java.util.*;

public final class HttpCatalogReader implements CatalogReader {
    private final RestClient http;
    public HttpCatalogReader(RestClient http) { this.http=http; }
    @Override public List<Location> locations() {
        try {
            var envelope=http.get().uri("/advice/locations").retrieve()
                    .body(new ParameterizedTypeReference<Envelope<List<Location>>>(){});
            if(envelope==null || envelope.code()!=1000 || envelope.result()==null || envelope.result().size()>1000)
                throw ChatFailure.catalog();
            Set<Integer> ids=new HashSet<>();
            for(var place:envelope.result()) if(place==null || place.id()<1 || !ids.add(place.id()) || place.name()==null
                    || place.name().isBlank() || place.name().length()>120 || place.canonicalId()<1 || place.aliasIds()==null
                    || place.aliasIds().size()>100 || !List.of("DEPARTURE","DESTINATION").contains(place.type())) throw ChatFailure.catalog();
            var indexed=new HashMap<Integer,Location>(); envelope.result().forEach(p->indexed.put(p.id(),p));
            for(var place:envelope.result()) {
                var canonical=indexed.get(place.canonicalId());
                if(canonical==null || !place.aliasIds().contains(place.id()) || !place.aliasIds().contains(place.canonicalId())
                        || !place.type().equals(canonical.type()) || place.region()!=canonical.region()
                        || place.aliasIds().stream().anyMatch(id->id==null || !indexed.containsKey(id)
                            || indexed.get(id).canonicalId()!=place.canonicalId() || !indexed.get(id).type().equals(place.type()))) throw ChatFailure.catalog();
            }
            return List.copyOf(envelope.result());
        } catch(RuntimeException failure) { throw ChatFailure.catalog(); }
    }
    @Override public SearchResult search(Criteria criteria,int page) {
        try {
            var envelope=http.post().uri("/advice/search").body(new SearchRequest(criteria,page,5)).retrieve()
                    .body(new ParameterizedTypeReference<Envelope<SearchResult>>(){});
            if(envelope==null || envelope.code()!=1000) throw ChatFailure.catalog();
            return checked(envelope.result(),page);
        } catch(RuntimeException failure) { throw ChatFailure.catalog(); }
    }
    static SearchResult checked(SearchResult data,int page) {
        if(data==null || data.tours()==null || data.tours().size()>5 || data.page()!=page || data.size()!=5 || data.searchSummary()==null)
            throw ChatFailure.catalog();
        var summary=data.searchSummary();
        if(summary.returnedCount()!=data.tours().size() || summary.matchingCatalogCount()<0 || summary.matchingBookableCount()<0
                || summary.matchingBookableCount()>summary.matchingCatalogCount() || summary.dataAsOf()==null) throw ChatFailure.catalog();
        var cards=new ArrayList<TourCard>(); Set<Integer> ids=new HashSet<>();
        for(var card:data.tours()) {
            if(card==null || card.tourId()<1 || !ids.add(card.tourId()) || !text(card.title(),400)
                    || !text(card.tourProgram(),80) || !text(card.destinationName(),120) || card.durationDays()<1
                    || !("/tours/detail/"+card.tourId()).equals(card.detailPath()) || card.price()==null || card.availability()==null)
                throw ChatFailure.catalog();
            var price=card.price(); var availability=card.availability();
            if(price.amountVnd()<0 || !"VND".equals(price.currency()) || price.basis()==null || price.checkedAt()==null
                    || availability.checkedAt()==null || !List.of("AVAILABLE","NO_FUTURE_DEPARTURE","NO_MATCHING_DEPARTURE","INSUFFICIENT_SEATS").contains(availability.status()))
                throw ChatFailure.catalog();
            if("AVAILABLE".equals(availability.status()) && (availability.departureId()==null || availability.departureId()<1
                    || availability.departureAt()==null || availability.availableSeats()==null || availability.availableSeats()<1)) throw ChatFailure.catalog();
            cards.add(new TourCard(card.tourId(),card.tourProgram(),card.title(),card.destinationName(),card.region(),
                    card.durationDays(),safeImage(card.imageUrl())?card.imageUrl():null,"/tours/detail/"+card.tourId(),price,availability));
        }
        return new SearchResult(List.copyOf(cards),summary,page,5);
    }
    private static boolean text(String value,int limit) { return value!=null && !value.isBlank() && value.length()<=limit; }
    private static boolean safeImage(String url) {
        if(url==null || url.length()>2048) return false;
        try { var uri=URI.create(url); return "https".equals(uri.getScheme()) && "res.cloudinary.com".equalsIgnoreCase(uri.getHost())
                && uri.getUserInfo()==null && uri.getFragment()==null && (uri.getPort()==-1 || uri.getPort()==443)
                && uri.getPath().matches("/[A-Za-z0-9_-]+/image/upload/.+"); }
        catch(RuntimeException invalid) { return false; }
    }
}
