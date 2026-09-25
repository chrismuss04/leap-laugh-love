package com.leap.leaplaughlove.order;

import com.leap.leaplaughlove.common.security.JwtService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Main entry point for the Order application.
 * OrderApplication
 */
@SpringBootApplication
@EnableScheduling
@Import(JwtService.class)
public class OrderApplication {
    /**
     * Entry point for the Order application.
     * @param args the command-line arguments passed to the application
     */
    public static void main(String[] args) {
        SpringApplication.run(OrderApplication.class, args);
    }
}

