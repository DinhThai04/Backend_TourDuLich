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
    private final TourSearchRepository repo;
    private final ElasticsearchOperations elasticsearchOperations;
    private final RedisTemplate<String, Object> redisTemplate;

    public TourDocument save(TourDocument doc) {
        // Nếu doc có id và đã tồn tại trong Elasticsearch → update
        if (repo.existsById(doc.getId())) {
            TourDocument existing = repo.findById(doc.getId()).orElse(null);
            if (existing != null) {
                existing.setDepartureDates(doc.getDepartureDates());
                doc = existing;
            }
        }

        // Lưu (create hoặc update đều dùng save)
        TourDocument saved = repo.save(doc);

        // Xóa cache Redis liên quan
        redisTemplate.delete("allTours");

        Set<String> keys = redisTemplate.keys("filter:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }

        return saved;
    }


    public Page<TourDocument> findAll(int page, int size) {
        redisTemplate.getConnectionFactory().getConnection().flushAll();

        String cacheKey = "allTours";
        Object cached = redisTemplate.opsForValue().get(cacheKey);

        if (cached != null) {
            System.out.println("Cache hit: " + cacheKey);
            CachedPage<TourDocument> cachedPage = (CachedPage<TourDocument>) cached;
            return new PageImpl<>(
                    cachedPage.getContent(),
                    PageRequest.of(cachedPage.getPage(), cachedPage.getSize()),
                    cachedPage.getTotal()
            );
        }

        System.out.println("Cache miss: " + cacheKey);
        Iterable<TourDocument> toursIterable = repo.findAll();
        List<TourDocument> tours = StreamSupport.stream(toursIterable.spliterator(), false)
                .collect(Collectors.toList());

        long totalHits = tours.size();
        int fromIndex = page * size;
        int toIndex = Math.min(fromIndex + size, tours.size());

        List<TourDocument> pagedList = new ArrayList<>();
        if (fromIndex < toIndex) {
            pagedList = new ArrayList<>(tours.subList(fromIndex, toIndex));
        }

        Page<TourDocument> pageResult = new PageImpl<>(pagedList, PageRequest.of(page, size), totalHits);

        CachedPage<TourDocument> cachedPage = new CachedPage<>(pagedList, page, size, totalHits);
        redisTemplate.opsForValue().set(cacheKey, cachedPage, 10, TimeUnit.MINUTES);

        return pageResult;
    }

    public Page<TourDocument> filterByDestinationAndPriceAndDate(
            String destinationLocation,
            String priceRange,
            String departureDate,
            int page,
            int size
    ) {
        String cacheKey = "filter:" + destinationLocation + ":" + priceRange + ":" + departureDate + ":page" + page + ":size" + size;
        Object cached = redisTemplate.opsForValue().get(cacheKey);

        if (cached != null) {
            System.out.println("Cache hit: " + cacheKey);
            return (Page<TourDocument>) cached;
        } else {
            System.out.println("Cache miss: " + cacheKey);
        }

        double minPrice = 0;
        double maxPrice = Double.MAX_VALUE;

        if (priceRange != null && !priceRange.isEmpty()) {
            switch (priceRange) {
                case "under5" -> maxPrice = 5_000_000;
                case "5to10" -> { minPrice = 5_000_000; maxPrice = 10_000_000; }
                case "10to20" -> { minPrice = 10_000_000; maxPrice = 20_000_000; }
                case "over20" -> minPrice = 20_000_000;
            }
        }

        Criteria criteria = new Criteria("basePrice")
                .greaterThanEqual(minPrice)
                .lessThanEqual(maxPrice);

        if (destinationLocation != null && !destinationLocation.isEmpty()) {
            criteria = criteria.and(new Criteria("destinationLocation").is(destinationLocation.trim()));
        }

        if (departureDate != null && !departureDate.isEmpty()) {
            criteria = criteria.and(new Criteria("departureDates").is(departureDate));
        }

        Query query = new CriteriaQuery(criteria)
                .setPageable(PageRequest.of(page, size));

        SearchHits<TourDocument> hits = elasticsearchOperations.search(query, TourDocument.class);

        List<TourDocument> result = hits.getSearchHits()
                .stream()
                .map(SearchHit::getContent)
                .collect(Collectors.toList());

        Page<TourDocument> tourPage = new PageImpl<>(result, PageRequest.of(page, size), hits.getTotalHits());

        redisTemplate.opsForValue().set(cacheKey, tourPage, 10, TimeUnit.MINUTES);
        return tourPage;

    }
}
