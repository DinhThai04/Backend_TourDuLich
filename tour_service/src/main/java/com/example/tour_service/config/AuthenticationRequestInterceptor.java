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
        ServletRequestAttributes servletRequestAttributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();

        var authHeader = servletRequestAttributes.getRequest().getHeader("Authorization");

        log.info("Header: {}", authHeader);
        if (StringUtils.hasText(authHeader))
            template.header("Authorization", authHeader);
    }
}

//@Component
//@Slf4j
//public class AuthenticationRequestInterceptor implements RequestInterceptor {
//    @Override
//    public void apply(RequestTemplate template) {
//        var attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
//        String authHeader = attrs.getRequest().getHeader("Authorization");
//        log.info("Feign sending Authorization: {}", authHeader);
//        if (authHeader != null && !authHeader.isEmpty()) {
//            template.header("Authorization", authHeader);
//        }
//    }
//}