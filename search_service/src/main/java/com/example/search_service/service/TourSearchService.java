package com.example.search_service.service;

import com.example.search_service.document.TourDocument;
import com.example.search_service.dto.CachedPage;
import com.example.search_service.repository.TourSearchRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.Criteria;
import org.springframework.data.elasticsearch.core.query.CriteriaQuery;
import org.springframework.data.elasticsearch.core.query.Query;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Service
@RequiredArgsConstructor
public class TourSearchService {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(TourSearchService.class);
    private static final int REINDEX_PAGE_SIZE = 500;
    /** index.max_result_window: Elasticsearch rejects from+size beyond this window. */
    private static final long MAX_RESULT_WINDOW = 10_000L;
    private static final int MAX_PAGE_SIZE = 100;
    /** Paging without a sort is not stable across ES refreshes, which duplicates/skips tours. */
    private static final org.springframework.data.domain.Sort STABLE_ORDER =
            org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.ASC, "sortKey");

    private final TourSearchRepository repo;
    private final ElasticsearchOperations elasticsearchOperations;
    private final RedisTemplate<String, Object> redisTemplate;

    private volatile boolean cacheDirty;
    public record SyncResult(int total,int updated,int deleted) {}
    public static void validate(TourDocument doc) {
        if(doc==null || doc.getId()<1 || doc.getTourTitle()==null || doc.getTourTitle().isBlank()
            || doc.getTourProgram()==null || doc.getTourProgram().isBlank()
            || doc.getBasePrice()==null || doc.getBasePrice().signum()<=0 || doc.getDepartureDates()==null)
            throw new IllegalArgumentException("Invalid tour index document");
        for(String date:doc.getDepartureDates()) {
            if(date==null || date.isBlank()) throw new IllegalArgumentException("Invalid departure date");
            java.time.LocalDate.parse(date);
        }
    }
    public synchronized SyncResult replaceSnapshot(List<TourDocument> snapshot) {
        var fresh=new java.util.LinkedHashMap<Integer,TourDocument>();
        for(var doc:snapshot) { validate(doc);if(fresh.put(doc.getId(),doc)!=null) throw new IllegalArgumentException("Duplicate tour ID"); }
        var old=new java.util.HashMap<Integer,TourDocument>();
        // Page through the index so a large catalogue never loads twice into memory at once.
        // Elasticsearch refuses from+size beyond max_result_window, so paging stops at that ceiling:
        // past it, comparing the remainder needs search_after, and an exception here would freeze the
        // index on every sync cycle instead of degrading.
        Page<TourDocument> batch = Page.empty();
        int lastPage = (int) (MAX_RESULT_WINDOW / REINDEX_PAGE_SIZE) - 1;
        for (int pageNumber = 0; pageNumber <= lastPage; pageNumber++) {
            // Same stable order as the read paths: unsorted paging can repeat or skip documents.
            batch = repo.findAll(PageRequest.of(pageNumber, REINDEX_PAGE_SIZE, STABLE_ORDER));
            for (var doc : batch) old.put(doc.getId(), doc);
            if (!batch.hasNext()) break;
            if (pageNumber == lastPage)
                log.error("search index is larger than the {} document paging window; documents beyond "
                        + "it are not compared and may stay stale", MAX_RESULT_WINDOW);
        }
        var changed=fresh.values().stream().filter(doc->!doc.equals(old.get(doc.getId()))).toList();
        var removed=old.keySet().stream().filter(id->!fresh.containsKey(id)).toList();
        if(changed.isEmpty() && removed.isEmpty()) return new SyncResult(fresh.size(),0,0);
        try {
            if(!changed.isEmpty()) repo.saveAll(changed);
            // Never remove old IDs until the complete source was validated and replacements saved.
            if(!removed.isEmpty()) repo.deleteAllById(removed);
            elasticsearchOperations.indexOps(TourDocument.class).refresh();
        } finally { invalidate(); }
        return new SyncResult(fresh.size(),changed.size(),removed.size());
    }
    private void invalidate() {
        cacheDirty=true;
        try { redisTemplate.opsForValue().increment("sgu-tour:search:v2:generation");cacheDirty=false; }
        catch(org.springframework.dao.DataAccessException unavailable) { /* Skip cache until invalidation can be retried. */ }
    }

    public Page<TourDocument> findAll(int page, int size) {
        PageRequest request = page(page, size);
        String key = prefix() + "all:" + page + ":" + size;
        Page<TourDocument> cached = cached(key, request);
        if (cached != null) return cached;
        Page<TourDocument> result = repo.findAll(request);
        cache(key, result);
        return result;
    }
    public Page<TourDocument> filterByDestinationAndPriceAndDate(
            String destinationLocation,
            String priceRange,
            String departureDate,
            int page,
            int size
    ) {
        PageRequest request = page(page, size);
        String criteriaKey = filterFingerprint(destinationLocation, priceRange, departureDate);
        String cacheKey = prefix() + "filter:" + criteriaKey + ":" + page + ":" + size;
        Page<TourDocument> cached = cached(cacheKey, request);
        if (cached != null) return cached;

        Criteria criteria = priceCriteria(priceRange);

        if (destinationLocation != null && !destinationLocation.isEmpty()) {
            criteria = criteria.and(new Criteria("destinationLocation").is(destinationLocation.trim()));
        }

        if (departureDate != null && !departureDate.isEmpty()) {
            criteria = criteria.and(new Criteria("departureDates").is(departureDate));
        }

        Query query = new CriteriaQuery(criteria)
                .setPageable(request);

        SearchHits<TourDocument> hits = elasticsearchOperations.search(query, TourDocument.class);

        List<TourDocument> result = hits.getSearchHits()
                .stream()
                .map(SearchHit::getContent)
                .collect(Collectors.toList());

        Page<TourDocument> tourPage = new PageImpl<>(result, request, hits.getTotalHits());

        cache(cacheKey, tourPage);
        return tourPage;

    }

    /** Half-open buckets [min, max) keep a tour priced exactly on a boundary in one bucket only. */
    private static Criteria priceCriteria(String priceRange) {
        if (priceRange == null || priceRange.isEmpty()) {
            return new Criteria("basePrice").greaterThanEqual(0);
        }
        return switch (priceRange) {
            case "under5" -> new Criteria("basePrice").lessThan(5_000_000);
            case "5to10" -> new Criteria("basePrice").greaterThanEqual(5_000_000).lessThan(10_000_000);
            case "10to20" -> new Criteria("basePrice").greaterThanEqual(10_000_000).lessThan(20_000_000);
            case "over20" -> new Criteria("basePrice").greaterThanEqual(20_000_000);
            // Dropping an unknown bucket would quietly return the whole catalogue; 400 says the
            // filter was not applied.
            default -> throw new IllegalArgumentException(
                    "Khoảng giá không hợp lệ; chọn một trong: under5, 5to10, 10to20, over20");
        };
    }

    private static PageRequest page(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE)
            throw new IllegalArgumentException("Trang phải >= 0; số tour mỗi trang từ 1 đến 100");
        if (((long) page + 1) * size > MAX_RESULT_WINDOW)
            throw new IllegalArgumentException("Trang quá sâu; chỉ xem được " + MAX_RESULT_WINDOW + " kết quả đầu tiên");
        return PageRequest.of(page, size, STABLE_ORDER);
    }

    /**
     * A bounded, deterministic cache key: raw user input never reaches Redis, while every field that
     * changes the query (destination, price bucket and date) stays part of the digest.
     */
    static String filterFingerprint(String destinationLocation, String priceRange, String departureDate) {
        String canonical = field(destinationLocation) + field(priceRange) + field(departureDate);
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Length prefixing keeps the concatenation unambiguous for any field content. */
    private static String field(String value) {
        String safe = value == null ? "" : value;
        return safe.length() + ":" + safe;
    }
    private String prefix() {
        if(cacheDirty) invalidate();
        try {
            Object generation = redisTemplate.opsForValue().get("sgu-tour:search:v2:generation");
            return "sgu-tour:search:v2:" + (generation instanceof Number ? generation : 0) + ":";
        } catch (org.springframework.dao.DataAccessException unavailable) { return "sgu-tour:search:v2:0:"; }
    }
    private Page<TourDocument> cached(String key, PageRequest page) {
        if(cacheDirty) return null;
        try {
            Object value = redisTemplate.opsForValue().get(key);
            if (value instanceof CachedPage<?> stored && stored.getContent() != null
                    && stored.getPage() == page.getPageNumber() && stored.getSize() == page.getPageSize()
                    && stored.getTotal() >= 0 && stored.getContent().size() <= page.getPageSize()
                    && stored.getContent().stream().allMatch(TourDocument.class::isInstance)) {
                return new PageImpl<>(stored.getContent().stream().map(TourDocument.class::cast).toList(), page, stored.getTotal());
            }
        } catch (org.springframework.data.redis.serializer.SerializationException | org.springframework.dao.DataAccessException invalidCache) {
            // Old/corrupt cache and a disconnected Redis must not break the Elasticsearch read.
        }
        return null;
    }
    private void cache(String key, Page<TourDocument> result) {
        if(cacheDirty) return;
        var value = new CachedPage<>(new ArrayList<>(result.getContent()), result.getNumber(), result.getSize(), result.getTotalElements());
        try { redisTemplate.opsForValue().set(key, value, 10, TimeUnit.MINUTES); }
        catch (org.springframework.data.redis.serializer.SerializationException | org.springframework.dao.DataAccessException unavailable) { /* Optional cache. */ }
    }
}
