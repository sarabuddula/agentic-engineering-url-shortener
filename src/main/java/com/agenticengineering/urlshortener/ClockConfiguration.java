package com.agenticengineering.urlshortener;

import com.agenticengineering.urlshortener.shared.port.TimeSource;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The production clock, shared by both planes through the TimeSource port. Truncated to microseconds, the precision the
 * database keeps, so a value read back equals the value returned when it was created (for example on a 200 replay).
 */
@Configuration
public class ClockConfiguration {

    @Bean
    TimeSource timeSource() {
        return () -> Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
