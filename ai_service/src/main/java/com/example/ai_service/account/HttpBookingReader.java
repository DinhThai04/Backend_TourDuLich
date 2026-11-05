package com.example.ai_service.account;

import com.example.ai_service.catalog.CatalogText;
import com.example.ai_service.config.LocalServiceUrl;
import com.example.ai_service.conversation.ChatFailure;
import com.example.ai_service.guard.BoundedHttp;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;

@Component
public final class HttpBookingReader implements BookingReader {
    private final RestClient http;
    private final ObjectMapper json;
    public HttpBookingReader(@Value("${app.ai.booking-base-url}") String base,ObjectMapper json) {
        LocalServiceUrl.requireLocal(base,"/booking","Invalid local booking service URL.");
        var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
        http=RestClient.builder().baseUrl(base).requestFactory(BoundedHttp.requests(client,Duration.ofSeconds(4)))
                .requestInterceptor(BoundedHttp.responseLimit(65536)).build();
        this.json=json;
    }
    @Override public Page mine(String authorization,Integer bookingId) {
        try {
            return http.get().uri(builder->{var b=builder.path("/advice/mine");if(bookingId!=null)b.queryParam("bookingId",bookingId);return b.build();})
                    .header("Authorization",authorization).exchange((request,response)->{
                        int status=response.getStatusCode().value();
                        if(status==401 || status==403) throw new ChatFailure(401,"LOGIN_REQUIRED");
                        if(status!=200) throw unavailable();
                        var root=json.readTree(response.getBody());
                        if(root==null || root.path("code").asInt()!=1000) throw unavailable();
                        var page=json.treeToValue(root.path("result"),Page.class);
                        if(page==null || page.bookings()==null || page.bookings().size()>MAX_PAGE_SIZE || page.checkedAt()==null) throw unavailable();
                        var ids=new HashSet<Integer>();
                        for(var b:page.bookings()) {
                            var cleanTitle=CatalogText.clean(b.tourTitle(),400);
                            if(b.bookingId()<1 || b.tourId()<1 || !ids.add(b.bookingId()) || b.tourTitle()==null || b.tourTitle().isBlank()
                                    || cleanTitle.truncated() || !cleanTitle.text().equals(b.tourTitle())
                                    || b.state()==null || !List.of("HELD","CHECKOUT","PAID","CANCELLED","EXPIRED","DEADLINE_PASSED",
                                            "DEPARTURE_PASSED","LEGACY_UNVERIFIED","REVIEW_REQUIRED","SCHEDULE_CHANGE_REQUIRED","REFUNDED").contains(b.state())
                                    || b.checkedAt()==null || !b.checkedAt().equals(page.checkedAt()) || !"reservation-v1".equals(b.ruleVersion())
                                    || b.remainingSeconds()<0 || bookingId!=null && b.bookingId()!=bookingId) throw unavailable();
                            if(List.of("HELD","CHECKOUT").contains(b.state()) && (b.createdAt()==null || b.departureAt()==null
                                    || b.paymentDeadline()==null || !b.paymentDeadline().isAfter(b.checkedAt())
                                    || b.paymentDeadline().isAfter(b.departureAt())
                                    || Duration.between(b.checkedAt(),b.paymentDeadline()).getSeconds()!=b.remainingSeconds())) throw unavailable();
                        }
                        return page;
                    });
        } catch(ChatFailure known) { throw known; }
        catch(Exception failure) { throw unavailable(); }
    }
    private static ChatFailure unavailable() { return new ChatFailure(503,"BOOKING_UNAVAILABLE"); }
}
