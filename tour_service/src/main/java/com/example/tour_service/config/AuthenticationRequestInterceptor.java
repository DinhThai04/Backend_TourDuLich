package com.example.tour_service.config;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
@Slf4j
public class AuthenticationRequestInterceptor implements RequestInterceptor {
    @Override
    public void apply(RequestTemplate template) {
        var requestId = org.slf4j.MDC.get("requestId");
        if (requestId != null) template.header("X-Request-ID", requestId);
        var attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servletRequestAttributes)) return;
        var authHeader = servletRequestAttributes.getRequest().getHeader("Authorization");
        if (StringUtils.hasText(authHeader))
            template.header("Authorization", authHeader);
    }
}
