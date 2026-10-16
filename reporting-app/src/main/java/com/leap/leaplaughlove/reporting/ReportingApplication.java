package com.leap.leaplaughlove.reporting;

import com.leap.leaplaughlove.common.security.JwtService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

/**
 * Spring Boot entry point for the reporting service: serves staff reports read from the reporting
 * schema, which reporting-etl loads from Kafka, and builds clients' monthly recaps from it on a
 * schedule. It never reads or writes operational data; recaps are the one thing it writes.
 */
@SpringBootApplication
@EnableScheduling
@Import(JwtService.class)
public class ReportingApplication {

    /**
     * Starts the reporting service.
     * @param args command-line arguments passed through to Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.run(ReportingApplication.class, args);
    }

    /**
     * The clock reporting periods are measured from. Reports work in UTC months, so it is UTC.
     * @return the system clock in UTC
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
