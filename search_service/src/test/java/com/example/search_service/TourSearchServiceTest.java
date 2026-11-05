package com.example.search_service;

import com.example.search_service.document.TourDocument;
import com.example.search_service.repository.TourSearchRepository;
import com.example.search_service.service.TourSearchService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.redis.core.RedisTemplate;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in destructive index fixtures run only against dedicated local test services, never demo defaults. */
@EnabledIfEnvironmentVariable(named="SGU_SEARCH_INTEGRATION",matches="true")
@SpringBootTest(properties={
    "spring.elasticsearch.uris=http://127.0.0.1:19200",
    "spring.data.redis.host=127.0.0.1","spring.data.redis.port=16379","spring.data.redis.database=15",
    "app.search.sync-enabled=false",
    "jwt.signerKey=search-integration-test-key-0123456789012345678901234567890123456789",
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration"
})
class TourSearchServiceIntegrationTest {
    @Autowired TourSearchService service;
    @Autowired TourSearchRepository repo;
    @Autowired RedisTemplate<String,Object> redis;
    @Autowired ElasticsearchOperations elasticsearch;
    @Autowired Environment environment;
    boolean isolated;
    @BeforeEach void isolatedFixtures() {
        assertEquals("http://127.0.0.1:19200",environment.getProperty("spring.elasticsearch.uris"));
        assertEquals("16379",environment.getProperty("spring.data.redis.port"));
        assertEquals("15",environment.getProperty("spring.data.redis.database"));
        isolated=true;
        repo.deleteAll();elasticsearch.indexOps(TourDocument.class).refresh();
        var keys=redis.keys("sgu-tour:search:v2:*");if(keys!=null && !keys.isEmpty())redis.delete(keys);
    }
    @AfterEach void cleanup() {
        if(!isolated)return;
        repo.deleteAll();elasticsearch.indexOps(TourDocument.class).refresh();
        var keys=redis.keys("sgu-tour:search:v2:*");if(keys!=null && !keys.isEmpty())redis.delete(keys);
    }
    TourDocument tour(int id,String title){return new TourDocument(id,"TEST_"+id,title,new BigDecimal("3000000"),"Origin","Destination","Bus",List.of("2026-12-01"));}
    @Test void snapshotSyncCreatesACompleteDocumentAndAdvancesTheCacheGeneration() {
        var doc=tour(1,"New tour");assertEquals(new TourSearchService.SyncResult(1,1,0),service.replaceSnapshot(List.of(doc)));
        assertEquals(doc,repo.findById(1).orElseThrow());
        var generation=redis.opsForValue().get("sgu-tour:search:v2:generation");
        assertInstanceOf(Number.class,generation);assertTrue(((Number)generation).longValue()>0);
    }
    @Test void snapshotSyncUpdatesTheIndexedPriceAndDepartureDates() {
        repo.save(tour(2,"Old title"));var changed=tour(2,"Updated title");
        changed.setBasePrice(new BigDecimal("4000000"));changed.setDepartureDates(List.of("2026-12-15"));
        service.replaceSnapshot(List.of(changed));assertEquals(changed,repo.findById(2).orElseThrow());
    }
    @Test void filtersMatchDestinationAndDepartureDateExactly() {
        repo.saveAll(java.util.stream.IntStream.rangeClosed(1,3).mapToObj(id->tour(id,"Tour "+id)).toList());
        elasticsearch.indexOps(TourDocument.class).refresh();
        // Keyword mapping keeps the stored casing, so the filter must match without normalisation.
        var result=service.filterByDestinationAndPriceAndDate("Destination","under5","2026-12-01",0,10);
        assertEquals(3,result.getTotalElements());
    }
    @Test void pagesRetainTotalAndPageMetadata() {
        repo.saveAll(java.util.stream.IntStream.rangeClosed(1,7).mapToObj(id->tour(id,"Tour "+id)).toList());
        elasticsearch.indexOps(TourDocument.class).refresh();
        for(int page=0;page<4;page++) {
            var result=service.findAll(page,3);assertEquals(page,result.getNumber());assertEquals(3,result.getSize());
            assertEquals(7,result.getTotalElements());assertEquals(page<2?3:page==2?1:0,result.getNumberOfElements());
        }
    }
}
