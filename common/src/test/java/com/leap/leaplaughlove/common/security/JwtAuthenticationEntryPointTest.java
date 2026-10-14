package com.leap.leaplaughlove.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("JwtAuthenticationEntryPoint Unit Tests")
class JwtAuthenticationEntryPointTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JwtAuthenticationEntryPoint entryPoint = new JwtAuthenticationEntryPoint(objectMapper);

    @Test
    @DisplayName("commence writes 401 UNAUTHORIZED status and standard JSON error response")
    void testCommence() throws IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(request, response, new BadCredentialsException("Invalid token"));

        assertEquals(MockHttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
        assertTrue(response.getContentType().startsWith(MediaType.APPLICATION_JSON_VALUE));

        Map<?, ?> responseBody = objectMapper.readValue(response.getContentAsString(), Map.class);
        assertEquals("UNAUTHORIZED", responseBody.get("error"));
        assertEquals("A valid bearer token is required", responseBody.get("message"));
    }

    @Test
    @DisplayName("writeUnauthorized helper writes 401 UNAUTHORIZED status and standard JSON error response")
    void testWriteUnauthorized() throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();

        JwtAuthenticationEntryPoint.writeUnauthorized(response, objectMapper);

        assertEquals(MockHttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
        assertTrue(response.getContentType().startsWith(MediaType.APPLICATION_JSON_VALUE));

        Map<?, ?> responseBody = objectMapper.readValue(response.getContentAsString(), Map.class);
        assertEquals("UNAUTHORIZED", responseBody.get("error"));
        assertEquals("A valid bearer token is required", responseBody.get("message"));
    }
}

