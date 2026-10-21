package com.agenticengineering.urlshortener.tests.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticengineering.urlshortener.shared.value.InvalidShortCodeException;
import com.agenticengineering.urlshortener.shared.value.InvalidTargetUrlException;
import com.agenticengineering.urlshortener.shared.value.ShortCode;
import com.agenticengineering.urlshortener.shared.value.TargetUrl;
import org.junit.jupiter.api.Test;

/**
 * T015: structural validation and equality of the shared value objects (FR-002, FR-013). Limits come from
 * contracts/openapi.yaml: short code 4-16 characters of [A-Za-z0-9]; target_url at most 2048 characters. Address rules
 * (D-003) and the full canonical form are tested by the URL validation suite (T029), not here.
 */
class SharedValueObjectsTest {

    // ---- ShortCode

    @Test
    void shortCodeAcceptsBase62WithinLengthBounds() {
        assertThat(ShortCode.of("aB3x").value()).isEqualTo("aB3x");
        assertThat(ShortCode.of("aB3xK9mQaB3xK9mQ").value()).hasSize(16);
    }

    @Test
    void shortCodeRejectsOutOfBoundsOrNonBase62() {
        for (String bad : new String[] {null, "", "abc", "aB3xK9mQaB3xK9mQz", "ab-c", "ab c", "abcé", "abc/"}) {
            assertThatThrownBy(() -> ShortCode.of(bad)).as("input %s", bad).isInstanceOf(InvalidShortCodeException.class);
        }
    }

    @Test
    void shortCodeEqualityIsByCaseSensitiveValue() {
        assertThat(ShortCode.of("abcd")).isEqualTo(ShortCode.of("abcd")).hasSameHashCodeAs(ShortCode.of("abcd"));
        assertThat(ShortCode.of("abcd")).isNotEqualTo(ShortCode.of("ABCD"));
        assertThat(ShortCode.of("abcd")).hasToString("abcd");
    }

    // ---- TargetUrl

    @Test
    void targetUrlAcceptsHttpAndHttps() {
        assertThat(TargetUrl.of("https://example.com/docs").value()).isEqualTo("https://example.com/docs");
        assertThat(TargetUrl.of("http://example.com").value()).isEqualTo("http://example.com");
    }

    @Test
    void targetUrlStripsSurroundingWhitespace() {
        assertThat(TargetUrl.of("  https://example.com/a \t\n").value()).isEqualTo("https://example.com/a");
    }

    @Test
    void targetUrlRejectsWithStableReasons() {
        assertRejected(null, InvalidTargetUrlException.Reason.BLANK);
        assertRejected("   ", InvalidTargetUrlException.Reason.BLANK);
        assertRejected("example.com/docs", InvalidTargetUrlException.Reason.MALFORMED);
        assertRejected("https://exa mple.com", InvalidTargetUrlException.Reason.MALFORMED);
        assertRejected("ftp://example.com/file", InvalidTargetUrlException.Reason.SCHEME_NOT_ALLOWED);
        assertRejected("javascript:alert(1)", InvalidTargetUrlException.Reason.SCHEME_NOT_ALLOWED);
        assertRejected("https:///only-a-path", InvalidTargetUrlException.Reason.MISSING_HOST);
    }

    @Test
    void targetUrlEnforcesTheContractSizeLimitAfterTrimming() {
        String prefix = "https://example.com/";
        String atLimit = prefix + "a".repeat(2048 - prefix.length());
        assertThat(TargetUrl.of("  " + atLimit + "  ").value()).hasSize(2048);
        assertRejected(atLimit + "a", InvalidTargetUrlException.Reason.TOO_LONG);
    }

    @Test
    void targetUrlEqualityIsByValue() {
        assertThat(TargetUrl.of(" https://example.com/a")).isEqualTo(TargetUrl.of("https://example.com/a"))
                .hasSameHashCodeAs(TargetUrl.of("https://example.com/a"));
        assertThat(TargetUrl.of("https://example.com/a")).isNotEqualTo(TargetUrl.of("https://example.com/b"));
    }

    private static void assertRejected(String input, InvalidTargetUrlException.Reason reason) {
        assertThatThrownBy(() -> TargetUrl.of(input))
                .as("input %s", input)
                .isInstanceOfSatisfying(InvalidTargetUrlException.class, e -> assertThat(e.reason()).isEqualTo(reason));
    }
}
