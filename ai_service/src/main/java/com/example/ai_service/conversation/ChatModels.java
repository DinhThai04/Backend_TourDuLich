package com.example.ai_service.conversation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.List;

public final class ChatModels {
    public static final String CONVERSATION_ID_PATTERN = "[A-Za-z0-9_-]{43}";
    public static final String REQUEST_ID_PATTERN = "[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}";
    /** One documented request cap: 16000 code points, i.e. at most 64000 UTF-8 bytes. */
    public static final int MAX_MESSAGE_POINTS = 16000;

    private ChatModels() {}
    public enum Region { NORTH, CENTRAL, SOUTH }
    public enum BudgetScope { PER_ADULT, TOTAL_GROUP }
    public enum SearchMode { BOOKABLE, CATALOG }
    public enum Intent { SEARCH_TOURS, MORE_TOURS, DETAIL_TOUR, COMPARE_TOURS, POLICY, CLARIFY, OUT_OF_SCOPE }
    public enum Action { SET, CLEAR }
    public enum Field {
        REGION, DESTINATION, ORIGIN, MAX_BUDGET_VND, BUDGET_SCOPE, DEPARTURE_FROM, DEPARTURE_TO,
        ADULTS, CHILDREN, TODDLERS, INFANTS, UNCLASSIFIED_CHILDREN, SEARCH_MODE
    }
    public enum Hint {
        NONE, AMBIGUOUS_REQUEST, BUDGET_UNIT_REQUIRED, DATE_REQUIRED, UNSUPPORTED_FILTER,
        UNKNOWN_DESTINATION, UNKNOWN_ORIGIN, BUDGET_SCOPE_REQUIRED, PASSENGER_DETAILS_REQUIRED,
        TOUR_SELECTION_REQUIRED, NO_MORE_RESULTS
    }
    public record Party(Integer adults, Integer children, Integer toddlers, Integer infants, Integer unclassifiedChildren) {}
    public record Criteria(Region region, Integer destinationId, Integer originId, Long maxBudgetVnd,
                           BudgetScope budgetScope, LocalDate departureFrom, LocalDate departureTo,
                           Party party, SearchMode searchMode) {
        public static Criteria empty() { return new Criteria(null,null,null,null,null,null,null,null,SearchMode.BOOKABLE); }
    }
    /** An absent operation preserves a field; CLEAR uses an empty value. No arbitrary JSON paths. */
    public record Change(@NotNull Field field, @NotNull Action action, @NotNull @Size(max=120) String value) {}
    public record Plan(@NotNull Intent intent, @NotNull Boolean reset,
                       @NotNull @Size(max=20) List<@NotNull @Valid Change> changes,
                       @NotNull @Size(max=3) List<@NotNull @Min(1) @Max(5) Integer> positions,
                       @NotNull Hint clarification) {}
    public record Created(String conversationId, long turnVersion, OffsetDateTime expiresAt) {
        @Override public String toString() { return "CreatedConversation[REDACTED]"; }
    }
    public record CreateRequest() {}
    public record ResetRequest(@NotBlank @Pattern(regexp=CONVERSATION_ID_PATTERN) String conversationId) {
        @Override public String toString() { return "ResetRequest[REDACTED]"; }
    }
    public record ChatRequest(@NotBlank @Pattern(regexp=CONVERSATION_ID_PATTERN) String conversationId,
                              @NotBlank @Pattern(regexp=REQUEST_ID_PATTERN) String requestId,
                              @NotBlank String message) {
        @Override public String toString() { return "ChatRequest[REDACTED]"; }
    }
    public record Clarification(String reason, List<String> fields, String question, List<String> suggestedReplies) {}
    public record Price(long amountVnd, String currency, BudgetScope basis, boolean isEstimate,
                        boolean singleSupplementIncluded, OffsetDateTime checkedAt) {}
    public record Availability(String status, Integer departureId, OffsetDateTime departureAt,
                               Integer availableSeats, OffsetDateTime checkedAt) {}
    public record TourCard(int tourId, String tourProgram, String title, String destinationName, Region region,
                           int durationDays, String imageUrl, String detailPath, Price price, Availability availability) {}
    public record Summary(int returnedCount, boolean hasMore, int matchingCatalogCount, int matchingBookableCount,
                          OffsetDateTime dataAsOf) {}
    public record SearchResult(List<TourCard> tours, Summary searchSummary, int page, int size) {}
    public record SearchRequest(Criteria criteria, int page, int size) {}
    public record ItineraryDay(int dayNumber,String title,String description,String meal) {}
    public record TourDetail(int tourId,String title,List<ItineraryDay> itinerary,OffsetDateTime dataAsOf,boolean truncated) {}
    public record ComparisonItem(int tourId,String title,int durationDays,String destinationName,
                                 long referenceAdultPriceVnd,boolean hasFutureDeparture) {}
    public record TourComparison(List<Integer> tourIds,List<ComparisonItem> items,OffsetDateTime dataAsOf) {}
    public record PolicyInfo(String policyKey,String policyVersion,String sourceLabel) {}
    public record Location(int id, String name, String type, Region region, int canonicalId,
                           List<Integer> aliasIds, boolean adviceSupported) {}
    public record ChatResult(String conversationId, String requestId, long turnVersion, String status, String reply,
                             Criteria criteria, List<TourCard> tours, Clarification clarification,
                             TourDetail detail, TourComparison comparison, PolicyInfo policy, Summary searchSummary, OffsetDateTime expiresAt) {
        @Override public String toString() { return "ChatResult[content=REDACTED, turnVersion="+turnVersion+"]"; }
    }
    public record Envelope<T>(int code, String message, T result) {
        public static <T> Envelope<T> ok(String message, T result) { return new Envelope<>(1000,message,result); }
    }
    public record HistoryTurn(String user, String assistant) {
        @Override public String toString() { return "HistoryTurn[REDACTED]"; }
    }
    public record State(Criteria criteria, Hint pending, List<Integer> latestPresentedTourIds,
                        int page, boolean hasMore, List<HistoryTurn> history, long version) {
        public static State empty() { return new State(Criteria.empty(),Hint.NONE,List.of(),0,false,List.of(),0); }
        @Override public String toString() { return "ConversationState[REDACTED]"; }
    }
    public record Draft(Criteria criteria, Hint pending, List<Integer> presentedIds, int page, boolean hasMore,
                        String status, String reply, List<TourCard> tours, Clarification clarification,
                        Summary summary, boolean clearHistory,TourDetail detail,TourComparison comparison,PolicyInfo policy) {
        public Draft(Criteria criteria,Hint pending,List<Integer> ids,int page,boolean hasMore,String status,String reply,
                     List<TourCard> tours,Clarification clarification,Summary summary,boolean clearHistory) {
            this(criteria,pending,ids,page,hasMore,status,reply,tours,clarification,summary,clearHistory,null,null,null);
        }
    }
}
