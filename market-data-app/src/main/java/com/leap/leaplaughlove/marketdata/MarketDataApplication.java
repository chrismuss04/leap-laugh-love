package com.leap.leaplaughlove.marketdata;

import com.leap.leaplaughlove.iam.security.JwtService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@Import(JwtService.class)
public class MarketDataApplication {
    public static void main(String[] args) {
        SpringApplication.run(MarketDataApplication.class, args);
    }
}
