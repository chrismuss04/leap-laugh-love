package com.leap.leaplaughlove.marketdata;

import com.leap.leaplaughlove.common.security.JwtService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot entry point for the market data service: simulates instrument prices,
 * ingests them into quotes and OHLC candles, and exposes them over REST and SSE.
 */
@SpringBootApplication
@EnableScheduling
@Import(JwtService.class)
public class MarketDataApplication {

    /**
     * Starts the market data service.
     * @param args command-line arguments passed through to Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.run(MarketDataApplication.class, args);
    }
}
