package com.example.booking_service.operations;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.LocalDate;
import java.util.Optional;
import java.util.List;
import java.util.Map;

@Component
public class ReportingOperationsClient {

    private static final Logger log = LoggerFactory.getLogger(ReportingOperationsClient.class);
    private static final String INTERNAL_KEY_HEADER = "X-Internal-Reporting-Key";

    private final RestClient client;
    private final String internalKey;
    private final boolean enabled;

    public ReportingOperationsClient(
            RestClient.Builder builder,
            @Value("${app.operations.reporting-base-url:http://localhost:8092/reporting}") String baseUrl,
            @Value("${app.operations.reporting-internal-key:}") String internalKey,
            @Value("${app.operations.reporting-enabled:false}") boolean enabled) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(1000);
        requestFactory.setReadTimeout(2000);
        this.client = builder.baseUrl(baseUrl).requestFactory(requestFactory).build();
        this.internalKey = internalKey;
        this.enabled = enabled;
    }

    public boolean available() {
        return enabled && internalKey != null && internalKey.length() >= 32;
    }

    private record AuditResponse(List<Map<String, Object>> content, boolean last, long totalElements) {}

    public OperationsInsights.AuditPage audit(int page, int size) {
        try {
            AuditResponse result = client.get()
                    .uri(uriBuilder -> uriBuilder.path("/internal/reports/audit").queryParam("page", page).queryParam("size", size).build())
                    .header(INTERNAL_KEY_HEADER, internalKey)
                    .retrieve()
                    .body(AuditResponse.class);
            if (result == null || result.content() == null) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Chưa có phản hồi nhật ký từ dịch vụ báo cáo");
            }
            return new OperationsInsights.AuditPage(result.content(), !result.last(), result.totalElements());
        } catch (RestClientException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Chưa tải được nhật ký từ dịch vụ báo cáo", exception);
        }
    }
    public Optional<Map<String, Object>> report(LocalDate from, LocalDate through) {
        if (!available()) return Optional.empty();
        try {
            Map<String, Object> body = client.get()
                    .uri(uriBuilder -> uriBuilder.path("/internal/reports/operations")
                            .queryParam("from", from)
                            .queryParam("through", through)
                            .build())
                    .header(INTERNAL_KEY_HEADER, internalKey)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            return Optional.ofNullable(body);
        } catch (RestClientException exception) {
            log.warn("Reporting service unavailable; using legacy operations report: {}",
                    exception.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
