package com.leap.leaplaughlove.account;

import com.leap.leaplaughlove.common.security.JwtService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * Main entrypoint for the AccountApplication Spring Boot application.
 */
@SpringBootApplication
@Import(JwtService.class)
public class AccountApplication {
    /**
     * Main method to launch the Spring Boot application.
     * @param args command-line arguments passed to the application
     */
    public static void main(String[] args) {
        SpringApplication.run(AccountApplication.class, args);
    }
}

