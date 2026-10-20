package com.agenticengineering.urlshortener.shared.dns;

import java.net.InetAddress;
import java.util.List;

/**
 * Creation-time resolution of a target URL's host (plan section 1, D-003). The explicit seam lets tests inject a stub
 * and lets the demonstration run offline in fixed-local mode (A-004).
 */
public interface UrlTargetResolver {

    /**
     * Resolves {@code host} to every address it maps to.
     *
     * @throws UnresolvedHostException when the host cannot be resolved; callers reject the target
     */
    List<InetAddress> resolve(String host);
}
