package com.example.booking_service.notification;

import com.example.booking_service.repository.httpclient.TourInternalClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Map;

/** Resolves an optional public image via Tour internal API; never queries foreign DB tables. */
@Component
public class MailTourImageResolver {
    private static final Logger log = LoggerFactory.getLogger(MailTourImageResolver.class);
    private final TourInternalClient tourClient;

    @Autowired
    public MailTourImageResolver(TourInternalClient tourClient) {
        this.tourClient = tourClient;
    }

    /** Legacy constructor retained for unit tests that only exercise URL validation. */
    public MailTourImageResolver(JdbcTemplate ignored) {
        this.tourClient = null;
    }

    public String findForDeparture(Integer departureId) {
        if (departureId == null || tourClient == null) return null;
        try {
            Map<String, Object> departure = tourClient.getDeparture(departureId);
            if (departure == null) return null;
            Object url = departure.get("image_url");
            return url instanceof String s && isPublicImage(s) ? s : null;
        } catch (Exception unavailable) {
            log.warn("Tour image unavailable for departure {} ({})", departureId, unavailable.getClass().getSimpleName());
            return null;
        }
    }

    static boolean isPublicImage(String value) {
        if (value == null || value.length() > 2048 || value.contains("\r") || value.contains("\n")) return false;
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && "res.cloudinary.com".equalsIgnoreCase(uri.getHost())
                    && uri.getUserInfo() == null && uri.getFragment() == null && (uri.getPort() == -1 || uri.getPort() == 443)
                    && uri.getPath() != null && uri.getPath().matches("/[A-Za-z0-9_-]+/image/upload/.+");
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}
