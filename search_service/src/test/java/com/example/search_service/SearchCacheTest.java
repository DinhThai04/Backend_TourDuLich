package com.example.search_service;

import com.example.search_service.document.TourDocument;
import com.example.search_service.repository.TourSearchRepository;
import com.example.search_service.service.TourSearchService;
import org.junit.jupiter.api.*;
import org.springframework.data.domain.*;
import org.springframework.data.elasticsearch.core.*;
import org.springframework.data.elasticsearch.core.query.Query;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SearchCacheTest {
    private final TourSearchRepository tours=mock(TourSearchRepository.class);
    private final ElasticsearchOperations search=mock(ElasticsearchOperations.class);
    @SuppressWarnings("unchecked") private final RedisTemplate<String,Object> redis=mock(RedisTemplate.class);
    @SuppressWarnings("unchecked") private final ValueOperations<String,Object> values=mock(ValueOperations.class);
    private final Map<String,byte[]> data=new HashMap<>();
    private final GenericJackson2JsonRedisSerializer codec=new GenericJackson2JsonRedisSerializer();
    private final TourSearchService service=new TourSearchService(tours,search,redis);
    /** Catalogue pages are read with a stable order so Elasticsearch paging cannot repeat rows. */
    private static PageRequest page(int number,int size){
        return PageRequest.of(number,size,Sort.by(Sort.Direction.ASC,"sortKey"));
    }
    @BeforeEach void cache() {
        when(redis.opsForValue()).thenReturn(values);
        // Snapshot replacement pages through the index, so answer any page request.
        lenient().when(tours.findAll(any(Pageable.class))).thenAnswer(i -> Page.empty(i.getArgument(0)));
        when(values.get(anyString())).thenAnswer(i->codec.deserialize(data.get(i.getArgument(0))));
        doAnswer(i->{data.put(i.getArgument(0),codec.serialize(i.getArgument(1)));return null;}).when(values).set(anyString(),any(),anyLong(),any(TimeUnit.class));
    }
    @Test void pageRoundtripUsesSerializableDtoAndNeverFlushesRedis() {
        var doc=new TourDocument(1,"P1","Tour",new BigDecimal("3000000"),"Origin","Destination","Bus",List.of("2026-11-20"));
        when(tours.findAll(page(0,10))).thenReturn(new PageImpl<>(List.of(doc),page(0,10),21));
        when(tours.findAll(page(1,10))).thenReturn(new PageImpl<>(List.of(),page(1,10),21));
        data.put("other-service:important",codec.serialize("preserve"));
        assertEquals(1,service.findAll(0,10).getContent().getFirst().getId());
        assertEquals(21,service.findAll(0,10).getTotalElements());
        service.findAll(1,10);
        verify(tours,times(1)).findAll(page(0,10));verify(tours,times(1)).findAll(page(1,10));
        verify(redis,never()).getConnectionFactory(); verify(redis,never()).keys(anyString());
        assertEquals("preserve",codec.deserialize(data.get("other-service:important")));
    }
    @Test void filteredPageAlsoSurvivesRedisSerialization() {
        @SuppressWarnings("unchecked") SearchHits<TourDocument> hits=mock(SearchHits.class);
        when(hits.getSearchHits()).thenReturn(List.of());when(hits.getTotalHits()).thenReturn(0L);
        when(search.search(any(Query.class),eq(TourDocument.class))).thenReturn(hits);
        service.filterByDestinationAndPriceAndDate("City","under5",null,0,10);
        service.filterByDestinationAndPriceAndDate("City","under5",null,0,10);
        verify(search,times(1)).search(any(Query.class),eq(TourDocument.class));
    }
    @Test void unknownPriceBucketIsRejectedBeforeQueryingTheIndex() {
        assertThrows(IllegalArgumentException.class,
                () -> service.filterByDestinationAndPriceAndDate("City","under_5",null,0,10));
        verify(search,never()).search(any(Query.class),eq(TourDocument.class));
    }
    @Test void savingTourInvalidatesOnlySearchPagesThroughGeneration() {
        String generation = "sgu-tour:search:v2:generation";
        when(values.increment(generation)).thenAnswer(i -> {
            Object old = codec.deserialize(data.get(generation));
            long next = old instanceof Number number ? number.longValue() + 1 : 1;
            data.put(generation, codec.serialize(next));
            return next;
        });
        when(tours.findAll(page(0,10))).thenReturn(Page.empty(page(0,10)));
        var doc = new TourDocument(1,"P1","Tour",new BigDecimal("3000000"),"Origin","Destination","Bus",List.of("2026-11-20"));
        when(search.indexOps(TourDocument.class)).thenReturn(mock(IndexOperations.class));
        data.put("another-service:key", codec.serialize("keep"));
        service.findAll(0,10); service.findAll(0,10);
        service.replaceSnapshot(List.of(doc));
        service.findAll(0,10);
        verify(tours,times(2)).findAll(page(0,10));
        assertEquals("keep",codec.deserialize(data.get("another-service:key")));
        verify(redis,never()).getConnectionFactory();
    }
    @Test void failedRedisFallsBackToElasticsearchAndInvalidPagingIsRejected() {
        when(values.get(anyString())).thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("fixture"));
        when(tours.findAll(page(0,10))).thenReturn(Page.empty(page(0,10)));
        assertTrue(service.findAll(0,10).isEmpty());
        assertThrows(IllegalArgumentException.class,()->service.findAll(-1,10));
        assertThrows(IllegalArgumentException.class,()->service.findAll(0,101));
    }
    @Test void resultWindowBoundaryIsEnforcedBeforeElasticsearch() {
        assertDoesNotThrow(()->service.findAll(9_999,1));
        assertThrows(IllegalArgumentException.class,()->service.findAll(10_000,1));
        // from+size of exactly 10000 stays legal, the next row would leave the window.
        assertDoesNotThrow(()->service.findAll(99,100));
        assertThrows(IllegalArgumentException.class,()->service.findAll(100,100));
        verify(search,never()).search(any(Query.class),eq(TourDocument.class));
    }
    @Test void filterCacheKeyIsBoundedDeterministicAndInputSensitive() {
        @SuppressWarnings("unchecked") SearchHits<TourDocument> hits=mock(SearchHits.class);
        when(hits.getSearchHits()).thenReturn(List.of());when(hits.getTotalHits()).thenReturn(0L);
        when(search.search(any(Query.class),eq(TourDocument.class))).thenReturn(hits);
        String destination="Đà Nẵng "+"x".repeat(400);
        service.filterByDestinationAndPriceAndDate(destination,"under5","2026-12-01",0,10);
        // Identical criteria reuse the cached page instead of re-querying Elasticsearch.
        service.filterByDestinationAndPriceAndDate(destination,"under5","2026-12-01",0,10);
        verify(search,times(1)).search(any(Query.class),eq(TourDocument.class));
        service.filterByDestinationAndPriceAndDate(destination+"!","under5","2026-12-01",0,10);
        service.filterByDestinationAndPriceAndDate(destination,"over20","2026-12-01",0,10);
        verify(search,times(3)).search(any(Query.class),eq(TourDocument.class));

        var keys=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(values,org.mockito.Mockito.atLeastOnce()).get(keys.capture());
        var pageKeys=keys.getAllValues().stream().filter(key->key.contains(":filter:")).toList();
        assertEquals(4,pageKeys.size());
        assertEquals(pageKeys.get(0),pageKeys.get(1));
        assertNotEquals(pageKeys.get(0),pageKeys.get(2));
        assertNotEquals(pageKeys.get(0),pageKeys.get(3));
        // Only the generation prefix, a fixed label and a 64-char digest reach Redis.
        assertTrue(pageKeys.stream().allMatch(key->key.matches("sgu-tour:search:v2:[0-9]+:filter:[0-9a-f]{64}:0:10")));
    }
}
