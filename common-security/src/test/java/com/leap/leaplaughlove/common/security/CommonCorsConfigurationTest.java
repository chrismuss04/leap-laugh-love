package com.leap.leaplaughlove.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("CommonCorsConfiguration Unit Tests")
class CommonCorsConfigurationTest {

    @Test
    @DisplayName("corsConfigurationSource configures open CORS policies for microservices")
    void testCorsConfigurationSource() {
        CorsConfigurationSource source = CommonCorsConfiguration.corsConfigurationSource();
        assertNotNull(source);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/test");
        CorsConfiguration config = source.getCorsConfiguration(request);

        assertNotNull(config);
        assertEquals(List.of("*"), config.getAllowedOriginPatterns());
        assertEquals(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"), config.getAllowedMethods());
        assertEquals(List.of("*"), config.getAllowedHeaders());
        assertEquals(Boolean.TRUE, config.getAllowCredentials());
    }

    @Test
    @DisplayName("private constructor can be instantiated via reflection without error")
    void testPrivateConstructor() throws Exception {
        Constructor<CommonCorsConfiguration> constructor = CommonCorsConfiguration.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        CommonCorsConfiguration instance = constructor.newInstance();
        assertNotNull(instance);
    }
}

