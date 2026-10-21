package com.agenticengineering.urlshortener;

import com.agenticengineering.urlshortener.shared.port.TimeSource;
import java.time.Instant;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The production clock, shared by both planes through the TimeSource port. */
@Configuration
public class ClockConfiguration {

    @Bean
    TimeSource timeSource() {
        return Instant::now;
    }
}
