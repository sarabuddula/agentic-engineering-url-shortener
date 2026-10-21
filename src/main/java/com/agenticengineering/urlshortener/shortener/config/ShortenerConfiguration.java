package com.agenticengineering.urlshortener.shortener.config;

import com.agenticengineering.urlshortener.shared.port.TimeSource;
import com.agenticengineering.urlshortener.shortener.domain.IdempotencyService;
import com.agenticengineering.urlshortener.shortener.domain.LinkRepository;
import com.agenticengineering.urlshortener.shortener.domain.LinkService;
import com.agenticengineering.urlshortener.shortener.domain.RandomBase62ShortCodeGenerator;
import com.agenticengineering.urlshortener.shortener.domain.ShortCodeGenerator;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Builds the framework-free application-plane domain objects (NFR-004: the domain itself has no Spring types). */
@Configuration
public class ShortenerConfiguration {

    @Bean
    ShortCodeGenerator shortCodeGenerator() {
        return new RandomBase62ShortCodeGenerator();
    }

    @Bean
    LinkService linkService(LinkRepository links, ShortCodeGenerator codes, TimeSource clock, ShortenerProperties properties) {
        return new LinkService(links, codes, clock, Duration.ofDays(properties.defaultTtlDays()));
    }

    @Bean
    IdempotencyService idempotencyService(LinkService links, LinkRepository repository) {
        return new IdempotencyService(links, repository);
    }
}
