package com.leap.leaplaughlove.iam.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.util.Map;
/**
 * Security configuration for the IAM application 
 * This class defines the JWT authentication and CORS settings, security filter chain, password encoder, and exception handling for unauthorized access.
 */
@Configuration
public class IamSecurityConfig {

    /**
     * Constructs a new IamSecurityConfig instance.
     */
    protected IamSecurityConfig() {
    }

    /**
     * Provides a password encoder bean for the IAM application.
     * @return BCryptPasswordEncoder instance
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Defines the security filter chain for the IAM application.
     * @param http the HttpSecurity object to configure
     * @param jwtService the JWT service for authentication
     * @param objectMapper the ObjectMapper for JSON serialization
     * @return the configured SecurityFilterChain
     * @throws Exception if an error occurs while configuring the security filter chain
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtService jwtService, ObjectMapper objectMapper) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // LLL-117: registration must stay public - a new applicant has no JWT yet.
                        .requestMatchers("/api/iam/auth/**", "/api/iam/v1/clients/register", "/actuator/health").permitAll()
                        // commment local manual-test ui only
                        .requestMatchers("/", "/index.html").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, authException) ->
                                writeUnauthorized(response, objectMapper)))
                .addFilterBefore(new JwtAuthenticationFilter(jwtService), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private void writeUnauthorized(HttpServletResponse response, ObjectMapper objectMapper) throws java.io.IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), Map.of(
                "error", "UNAUTHORIZED",
                "message", "A valid bearer token is required"));
    }
}
