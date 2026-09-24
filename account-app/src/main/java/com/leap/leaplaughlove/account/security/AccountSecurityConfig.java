package com.leap.leaplaughlove.account.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leap.leaplaughlove.common.security.CommonCorsConfiguration;
import com.leap.leaplaughlove.common.security.JwtAuthenticationEntryPoint;
import com.leap.leaplaughlove.common.security.JwtAuthenticationFilter;
import com.leap.leaplaughlove.common.security.JwtService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * Security configuration for the Account app.
 * Includes CORS, CSRF, Session Management, and JWT Authentication.
 */
@Configuration
public class AccountSecurityConfig {

    /**
     * Configures the security filter chain for the Account app, including CORS, CSRF, session management, and JWT authentication.
     * @param http the HttpSecurity object to configure
     * @param jwtService the service for handling JWT operations
     * @param objectMapper the ObjectMapper for JSON serialization
     * @return the configured SecurityFilterChain
     * @throws Exception if an error occurs during configuration
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtService jwtService, ObjectMapper objectMapper) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, authException) ->
                                JwtAuthenticationEntryPoint.writeUnauthorized(response, objectMapper)))
                .addFilterBefore(new JwtAuthenticationFilter(jwtService), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * Configures the CORS settings for the Account app.
     * @return the CorsConfigurationSource containing the CORS configuration
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        return CommonCorsConfiguration.corsConfigurationSource();
    }
}

