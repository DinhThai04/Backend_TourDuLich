package com.example.tour_service.advice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** Public catalog DTOs only. No account, booking or model credentials. */
public final class AdviceModels {
    private AdviceModels() {}
    public enum Region { NORTH, CENTRAL, SOUTH }
    public enum BudgetScope { PER_ADULT, TOTAL_GROUP }
    public enum SearchMode { BOOKABLE, CATALOG }
    public enum AvailabilityStatus { AVAILABLE, NO_FUTURE_DEPARTURE, NO_MATCHING_DEPARTURE, INSUFFICIENT_SEATS }
    public record Party(Integer adults, Integer children, Integer toddlers, Integer infants, Integer unclassifiedChildren) {}
    public record Criteria(Region region, Integer destinationId, Integer originId, BigDecimal maxBudgetVnd,
                           BudgetScope budgetScope, LocalDate departureFrom, LocalDate departureTo,
                           Party party, SearchMode searchMode) {}
    public record SearchRequest(Criteria criteria, Integer page, Integer size) {}
    public record Price(long amountVnd, String currency, BudgetScope basis, boolean isEstimate,
                        boolean singleSupplementIncluded, OffsetDateTime checkedAt) {}
    public record Availability(AvailabilityStatus status, Integer departureId, OffsetDateTime departureAt,
                               Integer availableSeats, OffsetDateTime checkedAt) {}
    public record TourCard(int tourId, String tourProgram, String title, String destinationName,
                           Region region, int durationDays, String imageUrl, String detailPath,
                           Price price, Availability availability) {
        public TourCard withImage(String url) {
            return new TourCard(tourId, tourProgram, title, destinationName, region, durationDays,
                    url, detailPath, price, availability);
        }
    }
    public record Summary(int returnedCount, boolean hasMore, int matchingCatalogCount,
                           int matchingBookableCount, OffsetDateTime dataAsOf) {}
    public record SearchResult(List<TourCard> tours, Summary searchSummary, int page, int size) {}
    public record LocationOption(int id, String name, String type, Region region,
                                  int canonicalId, List<Integer> aliasIds, boolean adviceSupported) {}
    public record ErrorDetail(String type, boolean retryable, Integer retryAfterSeconds) {}
    public record ErrorResponse(int code, String message, Object result, ErrorDetail error) {}
}
