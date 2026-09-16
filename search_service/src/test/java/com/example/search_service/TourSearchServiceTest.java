package com.example.search_service;

import com.example.search_service.document.TourDocument;
import com.example.search_service.repository.TourSearchRepository;
import com.example.search_service.service.TourSearchService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;


@SpringBootTest
class TourSearchServiceIntegrationTest {

    @Autowired
    private TourSearchService service;

    @Autowired
    private TourSearchRepository repo;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

//    @BeforeEach
//    void setUp() {
//        // Xóa dữ liệu Redis trước mỗi test
//        redisTemplate.delete("allTours");
//        Set<String> keys = redisTemplate.keys("filter:*");
//        if (keys != null && !keys.isEmpty()) {
//            redisTemplate.delete(keys);
//        }
//
//        // Xóa dữ liệu Elasticsearch hoặc DB nếu cần
//        repo.deleteAll();
//    }

    @Test
    void testSave_NewTour_ShouldCreateAndClearCache() {
        redisTemplate.opsForValue().set("allTours", "dummy cache");

        TourDocument doc = new TourDocument();
        doc.setId(1);
        doc.setDepartureDates(List.of("2025-01-01"));

        TourDocument result = service.save(doc);

        assertEquals(doc.getId(), result.getId());
        assertEquals(doc.getDepartureDates(), result.getDepartureDates());

        // Kiểm tra dữ liệu thực trong Redis
        Object cached = redisTemplate.opsForValue().get("allTours");
        assertNull(cached, "Cache 'allTours' phải bị xóa sau khi lưu tour mới");
    }

    @Test
    void testSave_ExistingTour_ShouldUpdateAndClearCache() {
        TourDocument doc = new TourDocument();
        doc.setId(2);
        doc.setDepartureDates(List.of("2025-02-01"));

        // Tạo trước dữ liệu tồn tại
        repo.save(doc);

        doc.setDepartureDates(List.of("2025-03-01"));
        TourDocument updated = service.save(doc);

        assertEquals(List.of("2025-03-01"), updated.getDepartureDates());
    }

    @Test
    void testFindAll_Pagination() {
        BigDecimal price = new BigDecimal(20);
        // Chuẩn bị dữ liệu 7 tour
        repo.saveAll(List.of(
                new TourDocument(1, "qwe", "t", price, "x", "y", "x", List.of("2025-01-01")),
                new TourDocument(2, "a", "b", price, "c", "d", "e", List.of("2025-01-02")),
                new TourDocument(3, "a", "b", price, "c", "d", "e", List.of("2025-01-03")),
                new TourDocument(4, "a", "b", price, "c", "d", "e", List.of("2025-01-04")),
                new TourDocument(5, "a", "b", price, "c", "d", "e", List.of("2025-01-05")),
                new TourDocument(6, "a", "b", price, "c", "d", "e", List.of("2025-01-06")),
                new TourDocument(7, "a", "b", price, "c", "d", "e", List.of("2025-01-07"))
        ));

        int pageSize = 3;

        // Gọi page 0
        Page<TourDocument> page0 = service.findAll(0, pageSize);
        assertEquals(3, page0.getContent().size());
        assertEquals(7, page0.getTotalElements());
        assertEquals(0, page0.getNumber());
        assertEquals(3, page0.getSize());
        assertEquals(List.of(1,2,3), page0.getContent().stream().map(TourDocument::getId).toList());

        // Gọi page 1
        Page<TourDocument> page1 = service.findAll(1, pageSize);
        assertEquals(3, page1.getContent().size());
        assertEquals(7, page1.getTotalElements());
        assertEquals(1, page1.getNumber());
        assertEquals(List.of(4,5,6), page1.getContent().stream().map(TourDocument::getId).toList());

        // Gọi page 2
        Page<TourDocument> page2 = service.findAll(2, pageSize);
        assertEquals(1, page2.getContent().size());
        assertEquals(7, page2.getTotalElements());
        assertEquals(2, page2.getNumber());
        assertEquals(List.of(7), page2.getContent().stream().map(TourDocument::getId).toList());

        // Gọi page quá lớn
        Page<TourDocument> page3 = service.findAll(3, pageSize);
        assertEquals(0, page3.getContent().size());
        assertEquals(7, page3.getTotalElements());
        assertEquals(3, page3.getNumber());
    }

}
