package com.agenticengineering.urlshortener.shortener.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticengineering.urlshortener.shared.value.InvalidTargetUrlException;
import com.agenticengineering.urlshortener.shared.value.InvalidTargetUrlException.Reason;
import com.agenticengineering.urlshortener.shared.value.TargetUrl;
import com.agenticengineering.urlshortener.tests.support.StubUrlTargetResolver;
import org.junit.jupiter.api.Test;

/**
 * T029: URL validation (FR-002, FR-003, FR-013, D-003 as resolved in plan section 8 with the 2026-10-18 additions)
 * and the canonical form of spec Amendment Appendix D. Hostnames are resolved through the stub resolver.
 */
class UrlValidationTest {

    private final StubUrlTargetResolver resolver = new StubUrlTargetResolver()
            .map("example.com", "93.184.216.34")
            .map("public.test", "203.0.113.10")
            .map("internal.test", "10.0.0.5")
            .map("mixed.test", "203.0.113.10", "192.168.0.1");
    private final UrlValidator validator = new UrlValidator(resolver);

    // ---- scheme, size, structure

    @Test
    void onlyHttpAndHttpsAreAccepted() {
        assertThat(validator.validate("https://example.com/docs").value()).isEqualTo("https://example.com/docs");
        assertThat(validator.validate("http://example.com/").value()).isEqualTo("http://example.com/");
        assertRejected("ftp://example.com/file", Reason.SCHEME_NOT_ALLOWED);
        assertRejected("javascript:alert(1)", Reason.SCHEME_NOT_ALLOWED);
        assertRejected("file:///etc/passwd", Reason.SCHEME_NOT_ALLOWED);
    }

    @Test
    void sizeAndBlankLimitsStillApply() {
        assertRejected("   ", Reason.BLANK);
        assertRejected("https://example.com/" + "a".repeat(2048), Reason.TOO_LONG);
    }

    // ---- canonical form (spec Amendment Appendix D)

    @Test
    void canonicalFormLowercasesSchemeAndHostAndDropsDefaultPorts() {
        assertThat(validator.validate("HTTPS://Example.com:443").value()).isEqualTo("https://example.com/");
        assertThat(validator.validate("https://example.com/")).isEqualTo(validator.validate("HTTPS://Example.com:443"));
        assertThat(validator.validate("http://EXAMPLE.com:80/a").value()).isEqualTo("http://example.com/a");
    }

    @Test
    void canonicalFormKeepsNonDefaultPortsPathQueryAndFragment() {
        assertThat(validator.validate("https://example.com:8443/CaseSensitive?Q=1#Frag").value())
                .isEqualTo("https://example.com:8443/CaseSensitive?Q=1#Frag");
        assertThat(validator.validate("http://example.com:443/x").value()).isEqualTo("http://example.com:443/x");
    }

    @Test
    void canonicalFormUppercasesPercentEncodingHex() {
        assertThat(validator.validate("https://example.com/a%2fb?q=%7e#%c3%a9").value())
                .isEqualTo("https://example.com/a%2Fb?q=%7E#%C3%A9");
    }

    // ---- D-003 rule set

    @Test
    void userinfoIsRejected() {
        assertRejected("https://user:secret@example.com/", Reason.USERINFO_NOT_ALLOWED);
        assertRejected("https://user@example.com/", Reason.USERINFO_NOT_ALLOWED);
    }

    @Test
    void localhostNamesAreRejectedWithoutResolution() {
        assertRejected("http://localhost/", Reason.LOCALHOST_NAME);
        assertRejected("http://LOCALHOST:8080/admin", Reason.LOCALHOST_NAME);
        assertRejected("http://api.localhost/", Reason.LOCALHOST_NAME);
        assertThat(resolver.lookups()).isEmpty();
    }

    @Test
    void internalIpv4LiteralsAreRejected() {
        for (String host : new String[] {"127.0.0.1", "127.255.0.9", "10.1.2.3", "172.16.0.1", "172.31.255.255",
                "192.168.1.1", "169.254.169.254", "169.254.0.7", "0.0.0.0"}) {
            assertRejected("http://" + host + "/", Reason.INTERNAL_ADDRESS);
        }
    }

    @Test
    void internalIpv6LiteralsAreRejected() {
        for (String host : new String[] {"[::1]", "[::]", "[fc00::1]", "[fd12:3456::1]", "[fe80::1]"}) {
            assertRejected("http://" + host + "/", Reason.INTERNAL_ADDRESS);
        }
    }

    @Test
    void ipv4MappedIpv6IsRejectedEvenForPublicAddresses() {
        assertRejected("http://[::ffff:127.0.0.1]/", Reason.IPV4_MAPPED_IPV6);
        assertRejected("http://[::ffff:8.8.8.8]/", Reason.IPV4_MAPPED_IPV6);
    }

    @Test
    void decimalOctalAndHexIpFormsAreRejected() {
        for (String host : new String[] {"2130706433", "0177.0.0.1", "0x7f.0.0.1", "127.1", "017700000001"}) {
            assertRejected("http://" + host + "/", Reason.NUMERIC_HOST_FORM);
        }
    }

    @Test
    void publicIpLiteralsAreAcceptedWithoutResolution() {
        assertThat(validator.validate("http://93.184.216.34/x").value()).isEqualTo("http://93.184.216.34/x");
        assertThat(validator.validate("http://172.32.0.1/").value()).isEqualTo("http://172.32.0.1/");
        assertThat(resolver.lookups()).isEmpty();
    }

    @Test
    void hostnamesAreCheckedAgainstEveryResolvedAddress() {
        assertThat(validator.validate("https://public.test/a").value()).isEqualTo("https://public.test/a");
        assertRejected("https://internal.test/", Reason.INTERNAL_ADDRESS);
        assertRejected("https://mixed.test/", Reason.INTERNAL_ADDRESS);
        assertRejected("https://unknown.test/", Reason.UNRESOLVABLE_HOST);
        assertThat(resolver.lookups()).contains("public.test", "internal.test", "mixed.test", "unknown.test");
    }

    private void assertRejected(String input, Reason reason) {
        assertThatThrownBy(() -> validator.validate(input))
                .as("input %s", input)
                .isInstanceOfSatisfying(InvalidTargetUrlException.class, e -> assertThat(e.reason()).isEqualTo(reason));
    }
}
