package com.peoplehub.support;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Leaves the test-only controllers ({@code SampleApiController}, {@code
 * NotificationTestController}) reachable without authentication (b2-3). They exist to test API
 * conventions and the notification pipeline, not authentication, and they only exist under their
 * own test profiles, so this chain does too. It matches only their paths; every other request still
 * goes through the application's deny-by-default chain.
 */
@Configuration(proxyBeanMethods = false)
@Profile({"api-test", "notification-test"})
public class TestEndpointsSecurityConfig {

    @Bean
    @Order(0)
    SecurityFilterChain testEndpointsSecurityFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/api/v1/api-test/**", "/api/v1/notification-test/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll());
        return http.build();
    }
}
