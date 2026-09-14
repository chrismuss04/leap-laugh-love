package com.leap.leaplaughlove.trading.security;

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
 * Security configuration for the Trading app
 * Includes CORS, CSRF, Session Management, and JWT Authentication
 */
@Configuration
public class TradingSecurityConfig {

    /**
     * Provides the security filter chain for the Trading app.
     * @param http the HttpSecurity object to configure
     * @param jwtService the JwtService for handling JWT tokens
     * @param objectMapper the ObjectMapper for writing JSON responses
     * @return SecurityFilterChain the configured security filter chain
     * @throws Exception if an error occurs while configuring security
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
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, authException) ->
                                JwtAuthenticationEntryPoint.writeUnauthorized(response, objectMapper)))
                .addFilterBefore(new JwtAuthenticationFilter(jwtService), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * Provides the CORS configuration source for the Trading app.
     * @return CorsConfigurationSource the configured CORS source
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        return CommonCorsConfiguration.corsConfigurationSource();
    }
}
