package com.leap.leaplaughlove.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;
import java.util.Map;

/**
 * AuthenticationEntryPoint that writes standard unauthorized JSON response when
 * JWT auth fails or is missing.
 */
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    /**
     * Constructor for an AuthenticationEntryPoint
     * 
     * @param objectMapper the object mapper for JSON serialization
     */
    public JwtAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Override the commence method to call writeUnauthorized helper
     * 
     * @see org.springframework.security.web.AuthenticationEntryPoint#commence(jakarta.servlet.http.HttpServletRequest,
     *      jakarta.servlet.http.HttpServletResponse,
     *      org.springframework.security.core.AuthenticationException)
     * @param request       the HTTP servlet request
     * @param response      the HTTP servlet response
     * @param authException the authentication exception
     * @throws IOException if writing output fails
     */
    @Override
    public void commence(HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        writeUnauthorized(response, objectMapper);
    }

    /**
     * Helper to write 401 UNAUTHORIZED response with standard error JSON.
     * 
     * @param response     the HTTP servlet response
     * @param objectMapper the object mapper for JSON serialization
     * @throws IOException if writing output fails
     */
    public static void writeUnauthorized(HttpServletResponse response, ObjectMapper objectMapper) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), Map.of(
                "error", "UNAUTHORIZED",
                "message", "A valid bearer token is required"));
    }
}
