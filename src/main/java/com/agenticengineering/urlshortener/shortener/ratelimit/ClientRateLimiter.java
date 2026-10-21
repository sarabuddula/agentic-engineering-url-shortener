package com.agenticengineering.urlshortener.shortener.ratelimit;

import com.agenticengineering.urlshortener.shared.port.TimeSource;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-client token buckets for one limit (T041; A-008a/b, FR-047, Bucket4j per research row 7). The map is bounded:
 * at the ceiling, clients idle for at least a full refill (burst / rate) are evicted first - removing them cannot
 * restore restricted capacity, since their bucket would be full anyway - and if the map is still full the new client
 * is rejected; active limits are never discarded (plan section 8). Limits are in memory and per instance (disclosed).
 */
public class ClientRateLimiter {

    /** Outcome of one consumption attempt; retryAfterSeconds is 0 when allowed. */
    public record Decision(boolean allowed, long retryAfterSeconds) {
    }

    private record Entry(Bucket bucket, Instant lastSeen) {
    }

    private final int requestsPerMinute;
    private final int burst;
    private final int maxClients;
    private final TimeSource clock;
    private final Map<String, Entry> clients = new ConcurrentHashMap<>();

    public ClientRateLimiter(int requestsPerMinute, int burst, int maxClients, TimeSource clock) {
        if (requestsPerMinute <= 0 || burst <= 0 || maxClients <= 0) {
            throw new IllegalArgumentException("rate, burst and client ceiling must be positive");
        }
        this.requestsPerMinute = requestsPerMinute;
        this.burst = burst;
        this.maxClients = maxClients;
        this.clock = Objects.requireNonNull(clock);
    }

    public Decision tryConsume(String clientKey) {
        Instant now = clock.now();
        Entry entry = clients.get(clientKey);
        if (entry == null) {
            entry = admit(clientKey, now);
            if (entry == null) {
                return new Decision(false, Math.max(1, (fullRefill().toMillis() + 999) / 1000));
            }
        }
        clients.put(clientKey, new Entry(entry.bucket(), now));
        ConsumptionProbe probe = entry.bucket().tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return new Decision(true, 0);
        }
        return new Decision(false, Math.max(1, (probe.getNanosToWaitForRefill() + 999_999_999L) / 1_000_000_000L));
    }

    /** Time for an empty bucket to refill completely: burst / rate. */
    public Duration fullRefill() {
        return Duration.ofMillis(burst * 60_000L / requestsPerMinute);
    }

    public int trackedClients() {
        return clients.size();
    }

    private synchronized Entry admit(String clientKey, Instant now) {
        Entry existing = clients.get(clientKey);
        if (existing != null) {
            return existing;
        }
        if (clients.size() >= maxClients) {
            Duration idleLimit = fullRefill();
            clients.entrySet().removeIf(e -> Duration.between(e.getValue().lastSeen(), now).compareTo(idleLimit) >= 0);
        }
        if (clients.size() >= maxClients) {
            return null;
        }
        Bucket bucket = Bucket.builder()
                .addLimit(Bandwidth.builder().capacity(burst).refillGreedy(requestsPerMinute, Duration.ofMinutes(1)).build())
                .build();
        Entry entry = new Entry(bucket, now);
        clients.put(clientKey, entry);
        return entry;
    }
}
