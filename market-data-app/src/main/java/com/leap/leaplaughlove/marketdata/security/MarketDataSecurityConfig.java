package com.leap.leaplaughlove.marketdata.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leap.leaplaughlove.common.security.CommonCorsConfiguration;
import com.leap.leaplaughlove.common.security.JwtAuthenticationEntryPoint;
import com.leap.leaplaughlove.common.security.JwtAuthenticationFilter;
import com.leap.leaplaughlove.common.security.JwtService;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * Configures Spring Security for the market data service: stateless JWT authentication
 * (reusing iam-app's {@link JwtService}) for every endpoint except health and error.
 */
@Configuration
public class MarketDataSecurityConfig {

    /**
     * Builds the security filter chain: stateless sessions, CORS, JWT authentication, and a
     * JSON 401 response for unauthenticated requests.
     * @param http the security configuration builder
     * @param jwtService the JWT service used to validate bearer tokens
     * @param objectMapper the object mapper used to write the JSON 401 response body
     * @return the configured security filter chain
     * @throws Exception if the security configuration cannot be built
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtService jwtService, ObjectMapper objectMapper) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health").permitAll()
                        // let Spring Boot's internal error forward render the real status instead
                        // of falling through to anyRequest().authenticated() and masking it as a 401
                        .requestMatchers("/error").permitAll()
                        // SSE emitters complete via an ASYNC re-dispatch that the JWT filter skips;
                        // the original REQUEST dispatch was already authenticated
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, authException) ->
                                JwtAuthenticationEntryPoint.writeUnauthorized(response, objectMapper)))
                .addFilterBefore(new JwtAuthenticationFilter(jwtService), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * Provides the CORS configuration applied to every endpoint.
     * @return the CORS configuration source
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        return CommonCorsConfiguration.corsConfigurationSource();
    }
}
