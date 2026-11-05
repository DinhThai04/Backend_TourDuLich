package com.example.booking_service.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.net.URI;

/** Resolves an optional public image; never downloads URLs or makes mail depend on image availability. */
@Component
public class MailTourImageResolver {
    private static final Logger log = LoggerFactory.getLogger(MailTourImageResolver.class);
    private final JdbcTemplate jdbc;
    public MailTourImageResolver(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public String findForDeparture(Integer departureId) {
        if (departureId == null) return null;
        try {
            var tours = jdbc.queryForList("SELECT tour_id FROM tour_departure WHERE id=?", Integer.class, departureId);
            if (tours.size() != 1) return null;
            // Bind a string instead of comparing CAST expressions with conflicting MySQL collations.
            var urls = jdbc.queryForList("SELECT url FROM file_mgmt WHERE tour_id=? ORDER BY id LIMIT 50",
                    String.class, tours.getFirst().toString());
            return urls.stream().filter(MailTourImageResolver::isPublicImage).findFirst().orElse(null);
        } catch (DataAccessException unavailable) {
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
        } catch (IllegalArgumentException invalid) { return false; }
    }
}
