package com.agenticengineering.urlshortener.tests.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticengineering.urlshortener.shared.dns.ResolverMode;
import com.agenticengineering.urlshortener.shared.dns.UnresolvedHostException;
import com.agenticengineering.urlshortener.shared.dns.UrlTargetResolver;
import com.agenticengineering.urlshortener.shortener.dns.FixedLocalResolver;
import com.agenticengineering.urlshortener.shortener.dns.LiveDnsResolver;
import com.agenticengineering.urlshortener.shortener.dns.UrlTargetResolvers;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/** T007: resolver seam with the committed fixed-local table (D-003, PVT-003). */
class FixedLocalResolverTest {

    private static final Path TABLE = Path.of("config/dns/fixed-local-resolver.yml");

    @Test
    void resolvesExampleComFromCommittedTable() {
        UrlTargetResolver resolver = FixedLocalResolver.fromYaml(TABLE);

        assertThat(resolver.resolve("example.com"))
                .extracting(InetAddress::getHostAddress)
                .containsExactly("93.184.216.34");
    }

    @Test
    void rejectsHostNotInTable() {
        UrlTargetResolver resolver = FixedLocalResolver.fromYaml(TABLE);

        assertThatThrownBy(() -> resolver.resolve("unlisted.example.net"))
                .isInstanceOf(UnresolvedHostException.class)
                .hasMessageContaining("unlisted.example.net");
    }

    @Test
    void hostLookupIsCaseInsensitive() {
        UrlTargetResolver resolver = FixedLocalResolver.fromYaml(TABLE);

        assertThat(resolver.resolve("EXAMPLE.COM"))
                .extracting(InetAddress::getHostAddress)
                .containsExactly("93.184.216.34");
    }

    @Test
    void modeSwitchSelectsImplementation() {
        Duration timeout = Duration.ofSeconds(3);

        assertThat(UrlTargetResolvers.forMode(ResolverMode.FIXED_LOCAL, TABLE, timeout))
                .isInstanceOf(FixedLocalResolver.class);
        assertThat(UrlTargetResolvers.forMode(ResolverMode.LIVE, TABLE, timeout))
                .isInstanceOf(LiveDnsResolver.class);
    }

    @Test
    void defaultResolverModeIsFixedLocal() throws Exception {
        try (InputStream in = Files.newInputStream(Path.of("src/main/resources/application.yml"))) {
            Map<String, Object> root = new Yaml().load(in);
            Map<?, ?> app = (Map<?, ?>) root.get("app");
            Map<?, ?> dns = (Map<?, ?>) app.get("dns");

            assertThat(dns.get("resolver-mode")).isEqualTo("fixed-local");
        }
    }
}
