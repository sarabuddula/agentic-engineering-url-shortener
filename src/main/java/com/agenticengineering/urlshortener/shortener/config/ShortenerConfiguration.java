package com.agenticengineering.urlshortener.shortener.config;

import com.agenticengineering.urlshortener.shared.dns.UrlTargetResolver;
import com.agenticengineering.urlshortener.shared.port.TimeSource;
import com.agenticengineering.urlshortener.shortener.analytics.AnalyticsStore;
import com.agenticengineering.urlshortener.shortener.analytics.RedirectAnalytics;
import com.agenticengineering.urlshortener.shortener.dns.UrlTargetResolvers;
import com.agenticengineering.urlshortener.shortener.domain.IdempotencyService;
import com.agenticengineering.urlshortener.shortener.domain.LinkRepository;
import com.agenticengineering.urlshortener.shortener.domain.LinkService;
import com.agenticengineering.urlshortener.shortener.domain.RandomBase62ShortCodeGenerator;
import com.agenticengineering.urlshortener.shortener.domain.ShortCodeGenerator;
import com.agenticengineering.urlshortener.shortener.domain.UrlValidator;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Builds the framework-free application-plane domain objects (NFR-004: the domain itself has no Spring types). */
@Configuration
@EnableScheduling
public class ShortenerConfiguration {

    /** fixed-local (demonstration default, no network) or live DNS, per app.dns.resolver-mode (plan section 8). */
    @Bean
    UrlTargetResolver urlTargetResolver(ShortenerProperties properties) {
        ShortenerProperties.Dns dns = properties.dns();
        return UrlTargetResolvers.forMode(dns.resolverMode(), dns.fixedLocalTable(), dns.lookupTimeout());
    }

    @Bean
    UrlValidator urlValidator(UrlTargetResolver resolver) {
        return new UrlValidator(resolver);
    }

    @Bean
    ShortCodeGenerator shortCodeGenerator() {
        return new RandomBase62ShortCodeGenerator();
    }

    @Bean
    LinkService linkService(LinkRepository links, ShortCodeGenerator codes, TimeSource clock, ShortenerProperties properties) {
        return new LinkService(links, codes, clock, Duration.ofDays(properties.defaultTtlDays()));
    }

    @Bean
    RedirectAnalytics redirectAnalytics(AnalyticsStore store, TimeSource clock) {
        return new RedirectAnalytics(store, clock);
    }

    @Bean
    IdempotencyService idempotencyService(LinkService links, LinkRepository repository) {
        return new IdempotencyService(links, repository);
    }
}
