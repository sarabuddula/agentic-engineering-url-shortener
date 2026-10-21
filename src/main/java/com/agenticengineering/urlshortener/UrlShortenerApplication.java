package com.agenticengineering.urlshortener;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Single fat-jar entry point for both planes (ADR-004): the application plane ({@code shortener}) and the control plane
 * ({@code orchestration}) share one JVM; their {@code @ConfigurationProperties} are registered by the scan.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class UrlShortenerApplication {

    public static void main(String[] args) {
        SpringApplication.run(UrlShortenerApplication.class, args);
    }
}
