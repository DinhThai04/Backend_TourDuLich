package com.example.search_service.repository;

import com.example.search_service.document.TourDocument;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

public interface TourSearchRepository extends ElasticsearchRepository<TourDocument, Integer> {

}
