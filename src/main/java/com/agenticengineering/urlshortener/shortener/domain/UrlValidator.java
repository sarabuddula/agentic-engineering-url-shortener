package com.agenticengineering.urlshortener.shortener.domain;

import com.agenticengineering.urlshortener.shared.dns.UnresolvedHostException;
import com.agenticengineering.urlshortener.shared.dns.UrlTargetResolver;
import com.agenticengineering.urlshortener.shared.value.InvalidTargetUrlException;
import com.agenticengineering.urlshortener.shared.value.InvalidTargetUrlException.Reason;
import com.agenticengineering.urlshortener.shared.value.TargetUrl;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * URL validation behind the resolver seam (T031; FR-002, FR-003, FR-013, D-003 per plan section 8). Returns the target
 * in the canonical form of spec Amendment Appendix D. The service never fetches targets; these checks reduce abuse at
 * creation time. Recorded limitation: DNS rebinding after creation is not re-checked.
 */
public class UrlValidator {

    private static final Pattern NUMERIC_LABEL = Pattern.compile("0x[0-9a-f]*|[0-9]+");
    private static final Pattern DOTTED_QUAD_OCTET = Pattern.compile("0|[1-9][0-9]{0,2}");
    private static final Pattern PERCENT_ENCODING = Pattern.compile("%[0-9a-fA-F]{2}");

    private final UrlTargetResolver resolver;

    public UrlValidator(UrlTargetResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver);
    }

    /**
     * @return the validated target in canonical form
     * @throws InvalidTargetUrlException with the reason for the first failed rule
     */
    public TargetUrl validate(String input) {
        URI uri = URI.create(structural(input).value());
        if (uri.getRawUserInfo() != null) {
            throw reject(Reason.USERINFO_NOT_ALLOWED, "user information in URL");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (host.startsWith("[")) {
            checkIpv6Literal(host.substring(1, host.length() - 1));
        } else if (isNumericHost(host)) {
            checkIpv4Literal(host);
        } else {
            checkHostname(host);
        }
        return TargetUrl.of(canonical(uri, host));
    }

    /**
     * Structural checks of {@link TargetUrl}. java.net.URI cannot parse some numeric IP forms (for example 127.1 or
     * 0x7f.0.0.1) as a host, so they first fail as MISSING_HOST; the raw authority is inspected to report the specific
     * NUMERIC_HOST_FORM reason instead.
     */
    private static TargetUrl structural(String input) {
        try {
            return TargetUrl.of(input);
        } catch (InvalidTargetUrlException e) {
            if (e.reason() == Reason.MISSING_HOST) {
                String authority = URI.create(input.strip()).getRawAuthority();
                if (authority != null) {
                    String host = authority.substring(authority.lastIndexOf('@') + 1).replaceFirst(":[0-9]*$", "");
                    if (!host.isEmpty() && isNumericHost(host.toLowerCase(Locale.ROOT))) {
                        throw reject(Reason.NUMERIC_HOST_FORM, "decimal, octal or hexadecimal IP form");
                    }
                }
            }
            throw e;
        }
    }

    private void checkIpv6Literal(String literal) {
        InetAddress address = literalAddress(literal);
        // Java turns IPv4-mapped IPv6 (any textual form) into an Inet4Address
        if (address instanceof Inet4Address) {
            throw reject(Reason.IPV4_MAPPED_IPV6, "IPv4-mapped IPv6 address");
        }
        if (isInternal(address)) {
            throw reject(Reason.INTERNAL_ADDRESS, "internal IPv6 address");
        }
    }

    private void checkIpv4Literal(String host) {
        String[] labels = host.split("\\.", -1);
        boolean dottedQuad = labels.length == 4;
        for (String label : labels) {
            dottedQuad &= DOTTED_QUAD_OCTET.matcher(label).matches() && Integer.parseInt(label) <= 255;
        }
        if (!dottedQuad) {
            throw reject(Reason.NUMERIC_HOST_FORM, "decimal, octal or hexadecimal IP form");
        }
        if (isInternal(literalAddress(host))) {
            throw reject(Reason.INTERNAL_ADDRESS, "internal IPv4 address");
        }
    }

    private void checkHostname(String host) {
        String name = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
        if (name.equals("localhost") || name.endsWith(".localhost")) {
            throw reject(Reason.LOCALHOST_NAME, "localhost name");
        }
        List<InetAddress> addresses;
        try {
            addresses = resolver.resolve(name);
        } catch (UnresolvedHostException e) {
            throw reject(Reason.UNRESOLVABLE_HOST, "host did not resolve");
        }
        if (addresses.isEmpty()) {
            throw reject(Reason.UNRESOLVABLE_HOST, "host resolved to no address");
        }
        for (InetAddress address : addresses) {
            if (isInternal(address)) {
                throw reject(Reason.INTERNAL_ADDRESS, "host resolves to an internal address");
            }
        }
    }

    /** A host whose labels are all decimal or 0x-prefixed numbers is an IP form; DNS names never end in one. */
    private static boolean isNumericHost(String host) {
        for (String label : host.split("\\.", -1)) {
            if (!NUMERIC_LABEL.matcher(label).matches()) {
                return false;
            }
        }
        return true;
    }

    /** D-003 internal ranges: loopback, unspecified, RFC 1918, link-local (incl. 169.254.169.254), fc00::/7. */
    static boolean isInternal(InetAddress address) {
        if (address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress()) {
            return true;
        }
        if (address instanceof Inet4Address) {
            return address.isSiteLocalAddress();
        }
        return address instanceof Inet6Address && (address.getAddress()[0] & 0xFE) == 0xFC;
    }

    private static InetAddress literalAddress(String literal) {
        try {
            return InetAddress.getByName(literal); // IP literal: parsed, no DNS lookup
        } catch (UnknownHostException e) {
            throw reject(Reason.MALFORMED, "invalid IP literal");
        }
    }

    /** Spec Amendment Appendix D: lowercase scheme and host, drop default port, "/" for an empty path, uppercase %XX. */
    private static String canonical(URI uri, String host) {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        boolean defaultPort = (scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443);
        StringBuilder out = new StringBuilder(scheme).append("://").append(host);
        if (port != -1 && !defaultPort) {
            out.append(':').append(port);
        }
        String path = uri.getRawPath();
        out.append(path == null || path.isEmpty() ? "/" : upperPercent(path));
        if (uri.getRawQuery() != null) {
            out.append('?').append(upperPercent(uri.getRawQuery()));
        }
        if (uri.getRawFragment() != null) {
            out.append('#').append(upperPercent(uri.getRawFragment()));
        }
        return out.toString();
    }

    private static String upperPercent(String raw) {
        Matcher m = PERCENT_ENCODING.matcher(raw);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, m.group().toUpperCase(Locale.ROOT));
        }
        return m.appendTail(out).toString();
    }

    private static InvalidTargetUrlException reject(Reason reason, String detail) {
        return new InvalidTargetUrlException(reason, detail);
    }
}
