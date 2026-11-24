package com.example.pricing_service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    // Only single prices and the internal batch read are anonymous; the full table stays with managers.
    private final String[] PUBLIC_URLS = {
        "/prices/{id:[0-9]+}",
        "/prices/batch"
    };

    private final CustomJwtDecoder customJwtDecoder;
    private final InternalPricingAuthenticationFilter internalPricingAuthenticationFilter;

    public SecurityConfig(CustomJwtDecoder customJwtDecoder,
            InternalPricingAuthenticationFilter internalPricingAuthenticationFilter) {
        this.customJwtDecoder = customJwtDecoder;
        this.internalPricingAuthenticationFilter = internalPricingAuthenticationFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity httpSecurity) throws Exception {
        httpSecurity.authorizeHttpRequests(request -> request
                .requestMatchers("/internal/**").permitAll()
                .requestMatchers(HttpMethod.GET, PUBLIC_URLS).permitAll()
                .requestMatchers(HttpMethod.POST, "/prices/batch").permitAll()
                .requestMatchers(HttpMethod.GET, "/prices").hasAnyAuthority("SGU_SUPER_ADMIN", "PERM:PRICE_GET_LIST")
                .requestMatchers(HttpMethod.POST, "/prices").hasAnyAuthority("SGU_SUPER_ADMIN", "PERM:PRICE_CREATE")
                .requestMatchers(HttpMethod.PUT, "/prices/**").hasAnyAuthority("SGU_SUPER_ADMIN", "PERM:PRICE_UPDATE")
                .anyRequest()
                .authenticated());
        httpSecurity.addFilterBefore(internalPricingAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        httpSecurity.oauth2ResourceServer(oauth2 -> oauth2.jwt(jwtConfigurer -> jwtConfigurer
                .decoder(customJwtDecoder)
                .jwtAuthenticationConverter(jwtAuthenticationConverter()))
                .authenticationEntryPoint(new JwtAuthenticationEntryPoint()));
        httpSecurity.csrf(AbstractHttpConfigurer::disable);

        return httpSecurity.build();
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter jwtGrantedAuthoritiesConverter = new JwtGrantedAuthoritiesConverter();
        jwtGrantedAuthoritiesConverter.setAuthorityPrefix("");

        JwtAuthenticationConverter jwtAuthenticationConverter = new JwtAuthenticationConverter();
        jwtAuthenticationConverter.setJwtGrantedAuthoritiesConverter(jwtGrantedAuthoritiesConverter);

        return jwtAuthenticationConverter;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

}
