package com.leap.leaplaughlove.trading;

import com.leap.leaplaughlove.iam.security.JwtService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * Main entry point for the trading application.    
 */
@SpringBootApplication
@Import(JwtService.class)
public class TradingApplication {
    public static void main(String[] args) {
        SpringApplication.run(TradingApplication.class, args);
    }
}
