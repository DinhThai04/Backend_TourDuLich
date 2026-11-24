package com.example.ai_service.catalog;

import com.example.ai_service.conversation.ChatModels.*;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

public interface TourFactsReader {
    record Facts(int id,String title,int durationDays,String destinationName,Long adultPriceVnd,
                 OffsetDateTime checkedAt,List<LocalDateTime> futureDepartures) {
        public Facts {
            if (futureDepartures != null) futureDepartures=List.copyOf(futureDepartures);
        }
        public boolean hasFutureDeparture() { return futureDepartures != null && !futureDepartures.isEmpty(); }
    }
    /** Null means the selected tour is confirmed missing, never a network/data error. */
    Facts facts(int tourId);
    /** Page answers can use the verified fields even when another part of the projection is unavailable. */
    default Facts pageFacts(int tourId) { return facts(tourId); }
    List<DepartureOption> upcomingDepartures(int tourId);
    record MeetingPoint(String address, String landmark, String arrivalTime) {}
    default MeetingPoint meetingPoint(int tourId, int departureId) { return null; }
    TourDetail detail(Facts facts);
}
