package com.example.tour_service.controller;
import com.example.tour_service.service.SearchCatalogSnapshot;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class SearchCatalogController {
    private final SearchCatalogSnapshot catalog;
    public SearchCatalogController(SearchCatalogSnapshot catalog) { this.catalog=catalog; }
    @GetMapping("/tours/search-snapshot")
    public ResponseEntity<SearchCatalogSnapshot.Snapshot> snapshot() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(catalog.read());
    }
}
