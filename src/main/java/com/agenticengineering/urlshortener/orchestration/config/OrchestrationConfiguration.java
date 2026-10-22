package com.agenticengineering.urlshortener.orchestration.config;

import com.agenticengineering.urlshortener.shared.port.IdGenerator;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Control-plane infrastructure: bounded stage and task pools (plan section 3) and run identifiers. */
@Configuration
public class OrchestrationConfiguration {

    /** Size of each pool; a simple bounded thread pool per plan section 14 (no actor framework). */
    static final int POOL_SIZE = 4;

    @Bean(destroyMethod = "shutdownNow")
    ExecutorService orchestrationStagePool() {
        return Executors.newFixedThreadPool(POOL_SIZE);
    }

    @Bean(destroyMethod = "shutdownNow")
    ExecutorService orchestrationTaskPool() {
        return Executors.newFixedThreadPool(POOL_SIZE);
    }

    @Bean
    IdGenerator idGenerator() {
        return () -> "run-" + UUID.randomUUID();
    }
}
