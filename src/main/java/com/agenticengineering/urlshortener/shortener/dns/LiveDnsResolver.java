package com.agenticengineering.urlshortener.shortener.dns;

import com.agenticengineering.urlshortener.shared.dns.UnresolvedHostException;
import com.agenticengineering.urlshortener.shared.dns.UrlTargetResolver;
import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Real DNS resolution ({@code live} mode), bounded by a lookup timeout (A-015: 3 s by default). */
public final class LiveDnsResolver implements UrlTargetResolver {

    private final Duration lookupTimeout;

    public LiveDnsResolver(Duration lookupTimeout) {
        this.lookupTimeout = lookupTimeout;
    }

    @Override
    public List<InetAddress> resolve(String host) {
        CompletableFuture<InetAddress[]> lookup = CompletableFuture.supplyAsync(() -> {
            try {
                return InetAddress.getAllByName(host);
            } catch (Exception e) {
                throw new UnresolvedHostException(host, "DNS lookup failed", e);
            }
        });
        try {
            return List.of(lookup.get(lookupTimeout.toMillis(), TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            lookup.cancel(true);
            throw new UnresolvedHostException(host, "DNS lookup exceeded " + lookupTimeout, e);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof UnresolvedHostException unresolved
                    ? unresolved
                    : new UnresolvedHostException(host, "DNS lookup failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UnresolvedHostException(host, "DNS lookup interrupted", e);
        }
    }
}
