package com.leap.leaplaughlove.order.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leap.leaplaughlove.common.security.CommonCorsConfiguration;
import com.leap.leaplaughlove.common.security.JwtAuthenticationEntryPoint;
import com.leap.leaplaughlove.common.security.JwtAuthenticationFilter;
import com.leap.leaplaughlove.common.security.JwtService;
// Session Timeout & Revocation
import com.leap.leaplaughlove.common.security.ClientSessionValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * Security configuration for the Order app.
 * Includes CORS, CSRF, Session Management, and JWT Authentication.
 */
// Session Timeout & Revocation: common-security is outside each app's component scan.
@org.springframework.context.annotation.Import(ClientSessionValidator.class)
@Configuration
public class OrderSecurityConfig {
    /**
     * Configures the security filter chain for the Order application, including CORS, CSRF, session management, and JWT authentication.
     * @param http the HttpSecurity object to configure
     * @param jwtService the JWT service used for authentication
     * @param objectMapper the ObjectMapper used for JSON serialization
     * @return the configured SecurityFilterChain
     * @throws Exception if an error occurs while configuring the security filter chain
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtService jwtService, ObjectMapper objectMapper, ClientSessionValidator sessions) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/error").permitAll()
                        // Client-only: staff tokens are refused here (403).
                        .anyRequest().hasRole("CLIENT"))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, authException) ->
                                JwtAuthenticationEntryPoint.writeUnauthorized(response, objectMapper)))
                .addFilterBefore(new JwtAuthenticationFilter(jwtService, sessions), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * Provides the CORS configuration source for the Order application.
     * Uses the CORS configuration defined in CommonCorsConfiguration.
     * @return the CorsConfigurationSource containing the CORS configuration
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        return CommonCorsConfiguration.corsConfigurationSource();
    }
}
