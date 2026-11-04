package com.example.ai_service.catalog;

import com.example.ai_service.conversation.ChatFailure;
import com.example.ai_service.conversation.ChatModels.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;
import java.io.IOException;
import java.time.*;
import java.util.*;

/** Reads existing legacy DTOs as a bounded projection. Ignores basePrice: adult fare comes from tourPrice. */
public final class HttpTourFactsReader implements TourFactsReader {
    private final RestClient http;
    private final ObjectMapper json;
    private final Clock clock;
    public HttpTourFactsReader(RestClient http,ObjectMapper json,Clock clock) { this.http=http; this.json=json; this.clock=clock; }
    @Override public Facts facts(int tourId) {
        if(tourId<1) throw ChatFailure.catalog();
        try {
            JsonNode data=http.get().uri("/tours/{id}",tourId).exchange((request,response)->{
                if(response.getStatusCode().value()==404) return null;
                JsonNode root=body(response);
                // Existing tour controller uses HTTP400/code1001 for a missing tour. Other 400s are failures.
                if(response.getStatusCode().value()==400 && root.path("code").isInt() && root.path("code").asInt()==1001) return null;
                return result(root,response);
            });
            if(data==null) return null;
            if(integer(data,"id")!=tourId) throw ChatFailure.catalog();
            int duration=integer(data,"duration"); if(duration<1) throw ChatFailure.catalog();
            String title=requiredText(data,"tourTitle",400);
            String destination=requiredText(data.path("destinationCity"),"city",120);
            var adult=data.path("tourPrice").path("adultPrice");
            if(!adult.isNumber()) throw ChatFailure.catalog();
            long amount=adult.decimalValue().longValueExact(); if(amount<0) throw ChatFailure.catalog();
            var departures=data.path("departures"); if(!departures.isArray() || departures.size()>10000) throw ChatFailure.catalog();
            boolean future=false; var now=LocalDateTime.now(clock);
            for(var departure:departures) {
                if(integer(departure,"tourId")!=tourId || integer(departure,"id")<1 || integer(departure,"availableSeats")<0)
                    throw ChatFailure.catalog();
                var at=LocalDateTime.parse(rawText(departure,"departureDate"));
                var back=LocalDateTime.parse(rawText(departure,"returnDate")); if(back.isBefore(at)) throw ChatFailure.catalog();
                // Future != bookable. Comparison labels do not claim that a full departure has seats.
                if(at.isAfter(now)) future=true;
            }
            return new Facts(tourId,title,duration,destination,amount,future,OffsetDateTime.now(clock));
        } catch(RuntimeException failure) { throw ChatFailure.catalog(); }
    }
    @Override public TourDetail detail(Facts facts) {
        try {
            JsonNode rows=http.get().uri("/itineraries/tour/{id}",facts.id())
                    .exchange((request,response)->result(body(response),response));
            if(rows==null || !rows.isArray() || rows.size()>30) throw ChatFailure.catalog();
            var days=new ArrayList<ItineraryDay>(); var seen=new HashSet<Integer>();
            int budget=6000; boolean truncated=false;
            // Always include every day; reject excessive day counts instead of silently dropping days.
            var ordered=new ArrayList<JsonNode>(); rows.forEach(ordered::add);
            ordered.sort(Comparator.comparingInt(row->integer(row,"dayNumber")));
            for(var row:ordered) {
                int day=integer(row,"dayNumber"); if(day<1 || day>facts.durationDays() || !seen.add(day)) throw ChatFailure.catalog();
                int remaining=ordered.size()-days.size();
                int share=Math.min(700,budget/remaining);
                var title=CatalogText.clean(rawText(row,"title"),Math.min(120,share));
                if(title.text().isBlank()) throw ChatFailure.catalog();
                var meal=CatalogText.clean(optionalText(row,"meal"),Math.min(80,share/4));
                int descriptionLimit=Math.max(1,share-count(title.text())-count(meal.text()));
                var description=CatalogText.clean(optionalText(row,"description"),descriptionLimit);
                budget-=count(title.text())+count(meal.text())+count(description.text());
                truncated|=title.truncated() || meal.truncated() || description.truncated();
                days.add(new ItineraryDay(day,title.text(),description.text(),meal.text()));
            }
            return new TourDetail(facts.id(),facts.title(),List.copyOf(days),OffsetDateTime.now(clock),truncated);
        } catch(RuntimeException failure) { throw ChatFailure.catalog(); }
    }
    private JsonNode body(ClientHttpResponse response) throws IOException {
        byte[] bytes=response.getBody().readNBytes(1_048_577);
        if(bytes.length>1_048_576) throw ChatFailure.catalog();
        var root=json.readTree(bytes); if(root==null) throw ChatFailure.catalog(); return root;
    }
    private JsonNode result(JsonNode root,ClientHttpResponse response) throws IOException {
        if(!response.getStatusCode().is2xxSuccessful() || !root.path("code").isInt() || root.path("code").asInt()!=1000
                || root.path("result").isNull() || root.path("result").isMissingNode()) throw ChatFailure.catalog();
        return root.path("result");
    }
    private int integer(JsonNode object,String field) {
        var value=object.path(field); if(!value.isIntegralNumber() || !value.canConvertToInt()) throw ChatFailure.catalog(); return value.intValue();
    }
    private String rawText(JsonNode object,String field) {
        var value=object.path(field); if(!value.isTextual()) throw ChatFailure.catalog(); return value.textValue();
    }
    private String optionalText(JsonNode object,String field) {
        return object.path(field).isNull() || object.path(field).isMissingNode()?"":rawText(object,field);
    }
    private String requiredText(JsonNode object,String field,int max) {
        var cleaned=CatalogText.clean(rawText(object,field),max);
        if(cleaned.text().isBlank() || cleaned.truncated()) throw ChatFailure.catalog(); return cleaned.text();
    }
    private int count(String value) { return value.codePointCount(0,value.length()); }
}
