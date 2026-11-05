package com.example.ai_service.catalog;

import com.example.ai_service.config.LocalServiceUrl;
import com.example.ai_service.conversation.ChatFailure;
import com.example.ai_service.guard.BoundedHttp;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods=false)
public class CatalogConfiguration {
    @Bean public CatalogReader catalogReader(@Value("${app.ai.catalog-base-url}") String baseUrl,ObjectMapper mapper) {
        return new HttpCatalogReader(localClient(baseUrl,mapper));
    }
    @Bean public TourFactsReader tourFactsReader(@Value("${app.ai.catalog-base-url}") String baseUrl,ObjectMapper mapper,Clock clock) {
        return new HttpTourFactsReader(localClient(baseUrl,mapper),mapper,clock);
    }
    private RestClient localClient(String baseUrl,ObjectMapper mapper) {
        LocalServiceUrl.requireLocal(baseUrl,"/api/v1/tour","Invalid local catalog URL.");
        var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
        var requests=BoundedHttp.requests(http,Duration.ofSeconds(4));
        var rest=RestClient.builder().baseUrl(baseUrl).requestFactory(requests).requestInterceptor(BoundedHttp.responseLimit(1048576))
                .messageConverters(converters->{ converters.removeIf(c->c instanceof MappingJackson2HttpMessageConverter);
                    converters.add(new MappingJackson2HttpMessageConverter(mapper)); })
                .defaultStatusHandler(status->!status.is2xxSuccessful(),(request,response)->{
                    throw ChatFailure.catalog();
                }).build();
        return rest;
    }
}
