package com.example.search_service.service;
import com.example.search_service.document.TourDocument;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.*;
import java.util.*;

@Component
public class CatalogSnapshotReader {
    private final ObjectMapper mapper;
    private final URI source;
    private final String sourceKey;
    public CatalogSnapshotReader(ObjectMapper mapper,@Value("${app.search.source-url:http://localhost:8086/tour/tours/search-snapshot}") String source,
                                 @Value("${app.search.source-key:}") String sourceKey) {
        this.mapper=mapper;this.source=URI.create(source);this.sourceKey=sourceKey;
        if(!List.of("http","https").contains(this.source.getScheme()) || this.source.getUserInfo()!=null)
            throw new IllegalArgumentException("Invalid catalog source URL");
    }
    public List<TourDocument> read() {
        HttpURLConnection connection=null;
        try {
            connection=(HttpURLConnection)source.toURL().openConnection();
            connection.setConnectTimeout(2000);connection.setReadTimeout(5000);connection.setInstanceFollowRedirects(false);
            if(sourceKey!=null && !sourceKey.isBlank()) connection.setRequestProperty("X-Catalog-Key",sourceKey);
            if(connection.getResponseCode()!=200) throw new IllegalStateException("Catalog unavailable");
            byte[] bytes;
            try(var stream=connection.getInputStream()) { bytes=stream.readNBytes(8*1024*1024+1); }
            if(bytes.length>8*1024*1024) throw new IllegalStateException("Catalog exceeds snapshot bound");
            var body=mapper.readTree(bytes);var tours=body.path("tours");
            if(!"SGU_TOUR_CATALOG_V1".equals(body.path("source").asText()) || !body.path("complete").asBoolean(false)
                || !tours.isArray() || tours.size()>10000 || !body.path("total").isInt() || body.path("total").asInt()!=tours.size())
                throw new IllegalStateException("Incomplete catalog snapshot");
            var result=new ArrayList<TourDocument>();var ids=new HashSet<Integer>();
            for(var node:tours) {
                var doc=mapper.treeToValue(node,TourDocument.class);doc.setSortKey(TourDocument.sortKey(doc.getId()));TourSearchService.validate(doc);
                if(!ids.add(doc.getId())) throw new IllegalStateException("Duplicate catalog ID");
                result.add(doc);
            }
            return List.copyOf(result);
        } catch(Exception failure) { throw new IllegalStateException("Could not read complete catalog snapshot",failure); }
        finally { if(connection!=null) connection.disconnect(); }
    }
}
