    package com.example.tour_service.config;

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

    @Configuration
    @EnableWebSecurity
    @EnableMethodSecurity
    public class SecurityConfig {

        // Only the catalogue reads the storefront needs are anonymous; admin listings stay authenticated.
        private static final String[] PUBLIC_URLS = {
                "/tours/list",
                "/tours/random-3",
                "/tours/search",
                // Service-to-service catalogue feed for the search/auth index. The route stays
                // permitAll so the key filter can reject callers without a JWT.
                "/tours/search-snapshot",
                "/tours/{id:[0-9]+}",
                "/vehicles/list",
                "/locations/all",
                "/locations/departures",
                "/locations/destinations",
                "/locations/departures/search",
                "/locations/destinations/search",
                "/locations/destinations/attractive",
                "/tour-departures/tour/{tourId:[0-9]+}",
                "/tour-departures/{id:[0-9]+}",
                "/itineraries/tour/{tourId:[0-9]+}"
        };

        private final CustomJwtDecoder customJwtDecoder;
        public SecurityConfig(CustomJwtDecoder customJwtDecoder) {
            this.customJwtDecoder = customJwtDecoder;
        }

        @Bean
        public SecurityFilterChain filterChain(HttpSecurity httpSecurity) throws Exception {
            httpSecurity.authorizeHttpRequests(request -> request
                    .requestMatchers(HttpMethod.POST, "/advice/search").permitAll()
                    .requestMatchers(HttpMethod.GET, "/advice/locations").permitAll()
                    .requestMatchers(HttpMethod.GET, "/tour-departures/*/capacity").authenticated()
                    // Admin/shop history view: past, suspended and cancelled departures.
                    .requestMatchers(HttpMethod.GET, "/tour-departures/tour/*/all")
                    .hasAnyAuthority("SGU_SUPER_ADMIN", "PERM:TOUR_DEPARTURES_GET_LIST", "PERM:SHOP_CHANGE_CREATE")
                    .requestMatchers(HttpMethod.GET, PUBLIC_URLS)
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/tours/*/images").hasAnyAuthority("SGU_SUPER_ADMIN", "PERM:TOURS_CREATE", "PERM:TOURS_UPDATE")
                    .requestMatchers(HttpMethod.POST, "/tours", "/tours/**").hasAnyAuthority("SGU_SUPER_ADMIN", "PERM:TOURS_CREATE")
                    .requestMatchers(HttpMethod.PUT, "/tours", "/tours/**").hasAnyAuthority("SGU_SUPER_ADMIN", "PERM:TOURS_UPDATE")
                    .requestMatchers(HttpMethod.DELETE, "/tours", "/tours/**").hasAnyAuthority("SGU_SUPER_ADMIN", "PERM:TOURS_DELETE")
                    .requestMatchers(HttpMethod.POST, "/tour-departures", "/tour-departures/**").hasAnyAuthority("SGU_SUPER_ADMIN", "PERM:TOUR_DEPARTURES_CREATE")
                    .requestMatchers(HttpMethod.PUT, "/tour-departures", "/tour-departures/**").hasAnyAuthority("SGU_SUPER_ADMIN", "PERM:TOUR_DEPARTURES_UPDATE")
                    .requestMatchers(HttpMethod.POST, "/locations", "/locations/**").hasAnyAuthority("SGU_SUPER_ADMIN", "PERM:LOCATIONS_CREATE")
                    .requestMatchers(HttpMethod.PUT, "/locations", "/locations/**").hasAnyAuthority("SGU_SUPER_ADMIN", "PERM:LOCATIONS_UPDATE")
                    .requestMatchers(HttpMethod.POST, "/itineraries", "/itineraries/**").hasAnyAuthority("SGU_SUPER_ADMIN", "PERM:ITINERARIES_CREATE")
                    .requestMatchers(HttpMethod.PUT, "/itineraries", "/itineraries/**").hasAnyAuthority("SGU_SUPER_ADMIN", "PERM:ITINERARIES_UPDATE")
                    .requestMatchers(HttpMethod.DELETE, "/itineraries", "/itineraries/**").hasAnyAuthority("SGU_SUPER_ADMIN", "PERM:ITINERARIES_DELETE")
                    .requestMatchers(HttpMethod.POST, "/vehicles", "/vehicles/**").hasAnyAuthority("SGU_SUPER_ADMIN", "PERM:VEHICLES_CREATE")
                    .requestMatchers(HttpMethod.PUT, "/vehicles", "/vehicles/**").hasAnyAuthority("SGU_SUPER_ADMIN", "PERM:VEHICLES_UPDATE")
                    .anyRequest()
                    .authenticated());

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
