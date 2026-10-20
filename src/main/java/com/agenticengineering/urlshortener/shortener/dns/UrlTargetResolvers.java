package com.agenticengineering.urlshortener.shortener.dns;

import com.agenticengineering.urlshortener.shared.dns.ResolverMode;
import com.agenticengineering.urlshortener.shared.dns.UrlTargetResolver;
import java.nio.file.Path;
import java.time.Duration;

/** Selects the resolver for {@code app.dns.resolver-mode}. */
public final class UrlTargetResolvers {

    private UrlTargetResolvers() {
    }

    public static UrlTargetResolver forMode(ResolverMode mode, Path fixedLocalTable, Duration lookupTimeout) {
        return switch (mode) {
            case FIXED_LOCAL -> FixedLocalResolver.fromYaml(fixedLocalTable);
            case LIVE -> new LiveDnsResolver(lookupTimeout);
        };
    }
}
