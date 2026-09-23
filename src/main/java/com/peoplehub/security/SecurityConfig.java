package com.peoplehub.security;

import com.peoplehub.security.principal.AuthenticatedContextFilter;
import com.peoplehub.security.principal.PrincipalJwtConverter;
import com.peoplehub.security.principal.PrincipalStatusQuery;
import com.peoplehub.security.principal.PrivateEndpointBearerTokenResolver;
import jakarta.servlet.DispatcherType;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * The API's one security filter chain (b2-3, B2-3 decisions).
 *
 * <ul>
 *   <li>Deny by default: only {@link PublicEndpoints} are reachable without authentication.
 *   <li>Everything else needs an ES256 access token in the {@code Authorization} header, verified
 *       by {@code JwtConfig}'s decoder and turned into the caller's {@code AuthenticatedPrincipal}
 *       by {@link PrincipalJwtConverter} after a database check (B2-3/14).
 *   <li>Stateless: no HTTP session, no form login, no HTTP Basic, no logout filter and no request
 *       cache. Nothing here creates a default user or prints a generated password.
 *   <li>Spring's session-oriented CSRF protection is off: bearer tokens are not sent automatically
 *       by a browser. The cookie-authenticated refresh and logout endpoints check their own
 *       double-submit token and Origin header (B2-3/11).
 *   <li>401 and 403 use the standard problem body (B2-3/16).
 *   <li>CORS allows only the configured app origin, with credentials (B2-3/11); a refused CORS
 *       request also gets the standard 403 problem body ({@link ProblemCorsProcessor}).
 * </ul>
 *
 * <p>The correlation id, actor id and request-log filters are ordered before this chain, so a 401
 * or 403 still carries a correlation id and is logged.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http,
            ProblemAuthenticationEntryPoint authenticationEntryPoint,
            ProblemAccessDeniedHandler accessDeniedHandler,
            CorsConfigurationSource corsConfigurationSource,
            JwtDecoder jwtDecoder,
            PrincipalStatusQuery principalStatusQuery,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver)
            throws Exception {
        // Not a bean: as one it would also run as a plain servlet filter, outside this chain.
        CorsFilter corsFilter = new CorsFilter(corsConfigurationSource);
        corsFilter.setCorsProcessor(new ProblemCorsProcessor(exceptionResolver));

        http.oauth2ResourceServer(
                        resourceServer ->
                                resourceServer
                                        .bearerTokenResolver(
                                                new PrivateEndpointBearerTokenResolver())
                                        .authenticationEntryPoint(authenticationEntryPoint)
                                        .accessDeniedHandler(accessDeniedHandler)
                                        .jwt(
                                                jwt ->
                                                        jwt.decoder(jwtDecoder)
                                                                .jwtAuthenticationConverter(
                                                                        new PrincipalJwtConverter(
                                                                                principalStatusQuery))))
                .addFilterAfter(
                        new AuthenticatedContextFilter(), BearerTokenAuthenticationFilter.class)
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .addFilterBefore(corsFilter, BearerTokenAuthenticationFilter.class)
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .exceptionHandling(
                        exceptions ->
                                exceptions
                                        .authenticationEntryPoint(authenticationEntryPoint)
                                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(
                        requests ->
                                requests
                                        // An error raised while handling a permitted request is
                                        // rendered, not turned into a 401.
                                        .dispatcherTypeMatchers(DispatcherType.ERROR)
                                        .permitAll()
                                        .requestMatchers(PublicEndpoints.matcher())
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated());
        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(AppOrigin appOrigin) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        appOrigin
                .value()
                .ifPresent(
                        origin -> {
                            CorsConfiguration cors = new CorsConfiguration();
                            cors.setAllowedOrigins(List.of(origin));
                            cors.setAllowedMethods(
                                    List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
                            cors.setAllowedHeaders(
                                    List.of(
                                            HttpHeaders.AUTHORIZATION,
                                            HttpHeaders.CONTENT_TYPE,
                                            "X-CSRF-Token",
                                            "X-Correlation-Id"));
                            cors.setExposedHeaders(List.of("X-Correlation-Id"));
                            cors.setAllowCredentials(true);
                            cors.setMaxAge(Duration.ofHours(1));
                            source.registerCorsConfiguration("/api/**", cors);
                        });
        return source;
    }
}
