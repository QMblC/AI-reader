package com.aireader.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

import java.io.IOException;
import java.time.Clock;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MobileAccessProperties.class)
public class SecurityConfiguration {
    @Bean
    FirebaseAppCheck firebaseAppCheck(MobileAccessProperties properties, Clock clock) {
        return FirebaseAppCheck.create(properties, clock);
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper mapper, FirebaseAppCheck appCheck,
            GuestSessions sessions, @Value("${app.rate-limit.requests-per-minute}") int rateLimit) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new MobileAccessFilter(appCheck, sessions, mapper), AnonymousAuthenticationFilter.class)
                .addFilterAfter(new AnalysisRateLimitFilter(mapper, rateLimit), MobileAccessFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(MobileAccessFilter.GUEST_SESSION_ENDPOINTS).permitAll()
                        .requestMatchers("/api/v1/analyses", "/api/v1/illustrations", "/api/v1/illustrations/**").authenticated()
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**",
                                "/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml").permitAll()
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, ex) -> forbidden(response, mapper))
                        .accessDeniedHandler((request, response, ex) -> forbidden(response, mapper)))
                .build();
    }

    private static void forbidden(HttpServletResponse response, ObjectMapper mapper) throws IOException {
        response.setStatus(403);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), Map.of("type", "about:blank", "title", "Forbidden",
                "status", 403, "detail", "Access to this resource is denied"));
    }
}
