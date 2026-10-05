package com.leap.leaplaughlove.marketdata;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.boot.SpringApplication;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

@DisplayName("MarketDataApplication entry point")
class MarketDataApplicationMainTest {

    @Test
    @DisplayName("main starts the Spring application with its arguments")
    void mainRunsTheApplication() {
        try (MockedStatic<SpringApplication> spring = mockStatic(SpringApplication.class)) {
            MarketDataApplication.main(new String[] {"--server.port=0"});

            spring.verify(() -> SpringApplication.run(any(Class.class), any(String[].class)));
        }
    }
}
