package com.example.tour_service.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The snapshot is a bulk internal feed; it must never be an anonymous way to pull the whole catalogue. */
class CatalogSnapshotAccessFilterTest {
    private static final String SNAPSHOT = "/tour/tours/search-snapshot";

    private MockHttpServletResponse read(CatalogSnapshotAccessFilter filter, String remote, String header)
            throws Exception {
        return perform(filter, SNAPSHOT, remote, header, 200, true);
    }

    private MockHttpServletResponse perform(CatalogSnapshotAccessFilter filter, String path, String remote,
                                            String header, int expected, boolean reachesController) throws Exception {
        var request = new MockHttpServletRequest("GET", path);
        request.setRemoteAddr(remote);
        if (header != null) request.addHeader(CatalogSnapshotAccessFilter.HEADER, header);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertEquals(expected, response.getStatus(), "body=" + response.getContentAsString());
        assertEquals(reachesController, chain.getRequest() != null, path + " controller reach");
        return response;
    }

    @Test void configuredKeyIsTheOnlyWayIn() throws Exception {
        var filter = new CatalogSnapshotAccessFilter("ci-catalog-key");
        read(filter, "10.0.0.8", "ci-catalog-key");

        var missingKey = perform(filter, SNAPSHOT, "10.0.0.8", null, 401, false);
        org.junit.jupiter.api.Assertions.assertTrue(missingKey.getContentAsString().contains("\"code\":1401"));
        perform(filter, SNAPSHOT, "127.0.0.1", "guessed-key", 401, false);
    }

    @Test void withoutAConfiguredKeyOnlyLoopbackCallersAreTrusted() throws Exception {
        var filter = new CatalogSnapshotAccessFilter("");
        read(filter, "127.0.0.1", null);
        read(filter, "0:0:0:0:0:0:0:1", null);
        // Same box, different interface: a LAN client must not pull the whole catalogue anonymously.
        perform(filter, SNAPSHOT, "10.0.0.8", null, 401, false);
    }

    @Test void otherStorefrontReadsAreUntouched() throws Exception {
        var filter = new CatalogSnapshotAccessFilter("ci-catalog-key");
        for (String path : new String[]{"/tour/tours", "/tour/tour-departures/1", "/tour/locations"})
            perform(filter, path, "203.0.113.9", null, 200, true);
    }
}
