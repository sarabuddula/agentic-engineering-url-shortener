package com.agenticengineering.urlshortener.tests.support;

import com.agenticengineering.urlshortener.shared.dns.UnresolvedHostException;
import com.agenticengineering.urlshortener.shared.dns.UrlTargetResolver;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Programmable resolver for tests (D-003): maps hostnames to IP literals, rejects unmapped hosts, and records every
 * lookup so tests can assert that resolution happened. Not thread-safe; one instance per test.
 */
public final class StubUrlTargetResolver implements UrlTargetResolver {

    private final Map<String, List<InetAddress>> table = new LinkedHashMap<>();
    private final List<String> lookups = new ArrayList<>();

    /** Maps {@code host} to the given IP literals (for example a private address to test internal-target rejection). */
    public StubUrlTargetResolver map(String host, String... ipLiterals) {
        List<InetAddress> addresses = new ArrayList<>();
        for (String ip : ipLiterals) {
            try {
                addresses.add(InetAddress.getByName(ip));
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("not an IP literal: " + ip, e);
            }
        }
        table.put(host, List.copyOf(addresses));
        return this;
    }

    @Override
    public List<InetAddress> resolve(String host) {
        lookups.add(host);
        List<InetAddress> addresses = table.get(host);
        if (addresses == null) {
            throw new UnresolvedHostException(host, "not mapped in the test stub");
        }
        return addresses;
    }

    public List<String> lookups() {
        return Collections.unmodifiableList(lookups);
    }
}
