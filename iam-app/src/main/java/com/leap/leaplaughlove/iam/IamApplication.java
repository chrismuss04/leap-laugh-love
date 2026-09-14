package com.leap.leaplaughlove.iam;

import com.leap.leaplaughlove.common.security.JwtService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * Main class for the IAM Spring Boot application
 */
@SpringBootApplication
@Import(JwtService.class)
public class IamApplication {
    /**
     * Default constructor for the IAM application.
     */
    public IamApplication() {
    }

    /**
     * The main entry point for the IAM Spring Boot application.
     * @param args the command-line arguments passed to the application
     */
    public static void main(String[] args) {
        SpringApplication.run(IamApplication.class, args);
    }
}
