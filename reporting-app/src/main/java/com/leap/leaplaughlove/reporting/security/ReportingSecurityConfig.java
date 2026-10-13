package com.leap.leaplaughlove.reporting.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leap.leaplaughlove.common.security.ClientSessionValidator;
import com.leap.leaplaughlove.common.security.CommonCorsConfiguration;
import com.leap.leaplaughlove.common.security.JwtAuthenticationEntryPoint;
import com.leap.leaplaughlove.common.security.JwtAuthenticationFilter;
import com.leap.leaplaughlove.common.security.JwtService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * Configures Spring Security for the reporting service: stateless JWT authentication (reusing
 * iam-app's {@link JwtService}), with every report for commercial analysts only.
 */
// Session Timeout & Revocation: common-security is outside each app's component scan.
@Import(ClientSessionValidator.class)
@Configuration
public class ReportingSecurityConfig {

    /**
     * Builds the security filter chain: stateless sessions, CORS, JWT authentication, and a
     * JSON 401 response for unauthenticated requests.
     * @param http the security configuration builder
     * @param jwtService the JWT service used to validate bearer tokens
     * @param objectMapper the object mapper used to write the JSON 401 response body
     * @param sessions checks that a token's session is still live
     * @return the configured security filter chain
     * @throws Exception if the security configuration cannot be built
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtService jwtService, ObjectMapper objectMapper,
                                           ClientSessionValidator sessions) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health").permitAll()
                        // let Spring Boot's internal error forward render the real status instead
                        // of falling through to the role check and masking it as a 401
                        .requestMatchers("/error").permitAll()
                        // Analyst-only: client and trading operations tokens are refused here (403).
                        .anyRequest().hasRole("COMMERCIAL_ANALYST"))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, authException) ->
                                JwtAuthenticationEntryPoint.writeUnauthorized(response, objectMapper)))
                .addFilterBefore(new JwtAuthenticationFilter(jwtService, sessions), UsernamePasswordAuthenticationFilter.class);
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
