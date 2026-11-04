package com.example.ai_service.catalog;

import com.example.ai_service.conversation.ChatModels.*;
import java.time.OffsetDateTime;

public interface TourFactsReader {
    record Facts(int id,String title,int durationDays,String destinationName,long adultPriceVnd,
                 boolean hasFutureDeparture,OffsetDateTime checkedAt) {}
    /** Null means the selected tour is confirmed missing, never a network/data error. */
    Facts facts(int tourId);
    TourDetail detail(Facts facts);
}
