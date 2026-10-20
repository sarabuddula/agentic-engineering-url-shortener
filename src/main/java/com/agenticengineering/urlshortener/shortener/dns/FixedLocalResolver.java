package com.agenticengineering.urlshortener.shortener.dns;

import com.agenticengineering.urlshortener.shared.dns.UnresolvedHostException;
import com.agenticengineering.urlshortener.shared.dns.UrlTargetResolver;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.Yaml;

/**
 * Resolves hosts from a fixed local table and never queries DNS: the demonstration default, offline-safe and
 * deterministic (quickstart "Fixed-local resolver"). Hosts absent from the table are unresolved.
 */
public final class FixedLocalResolver implements UrlTargetResolver {

    private static final Pattern IPV4_LITERAL = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    private final Map<String, InetAddress> table;

    public FixedLocalResolver(Map<String, String> entries) {
        Map<String, InetAddress> parsed = new HashMap<>();
        entries.forEach((host, address) -> parsed.put(normalize(host), toLiteralAddress(host, address)));
        this.table = Map.copyOf(parsed);
    }

    /** Loads the {@code entries} map of the committed table, e.g. {@code config/dns/fixed-local-resolver.yml}. */
    public static FixedLocalResolver fromYaml(Path tableFile) {
        try (InputStream in = Files.newInputStream(tableFile)) {
            Map<String, Object> root = new Yaml().load(in);
            Object entries = root == null ? null : root.get("entries");
            if (!(entries instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("No 'entries' map in " + tableFile);
            }
            Map<String, String> table = new HashMap<>();
            map.forEach((host, address) -> table.put(String.valueOf(host), String.valueOf(address)));
            return new FixedLocalResolver(table);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read fixed-local resolver table " + tableFile, e);
        }
    }

    @Override
    public List<InetAddress> resolve(String host) {
        InetAddress address = table.get(normalize(host));
        if (address == null) {
            throw new UnresolvedHostException(host, "not in the fixed-local table");
        }
        return List.of(address);
    }

    private static String normalize(String host) {
        return host.trim().toLowerCase(Locale.ROOT);
    }

    // Only IP literals are accepted, so building the table can never trigger a DNS query.
    private static InetAddress toLiteralAddress(String host, String address) {
        String value = address.trim();
        if (!IPV4_LITERAL.matcher(value).matches() && !value.contains(":")) {
            throw new IllegalArgumentException("Entry for '" + host + "' is not an IP literal: " + value);
        }
        try {
            return InetAddress.getByName(value);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Entry for '" + host + "' is not a valid IP literal: " + value, e);
        }
    }
}
