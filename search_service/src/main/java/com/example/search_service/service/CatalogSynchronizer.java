package com.example.search_service.service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class CatalogSynchronizer {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(CatalogSynchronizer.class);
    private final CatalogSnapshotReader source;
    private final TourSearchService index;
    private final boolean enabled;
    public CatalogSynchronizer(CatalogSnapshotReader source,TourSearchService index,@Value("${app.search.sync-enabled:true}") boolean enabled) {
        this.source=source;this.index=index;this.enabled=enabled;
    }
    // The admin endpoint forces a sync, so the periodic pass only needs to catch missed events.
    // The fallback matches application.yml; a stale number here would silently change the cadence
    // for any profile that omits app.search.
    @Scheduled(initialDelayString="${app.search.sync-initial-delay-ms:2000}",fixedDelayString="${app.search.sync-delay-ms:60000}")
    public void poll() {
        if(!enabled) return;
        try { sync(); } catch(RuntimeException unavailable) { log.warn("Catalog sync deferred; keeping current index ({})",unavailable.getClass().getSimpleName()); }
    }
    public TourSearchService.SyncResult sync() {
        try { return index.replaceSnapshot(source.read()); }
        catch(RuntimeException unavailable) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Chưa đồng bộ được danh mục. Hệ thống sẽ thử lại."); }
    }
}
