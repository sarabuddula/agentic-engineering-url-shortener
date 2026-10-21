package com.agenticengineering.urlshortener.tests.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticengineering.urlshortener.shared.dns.UnresolvedHostException;
import com.agenticengineering.urlshortener.shortener.config.ShortenerProperties;
import com.agenticengineering.urlshortener.tests.support.DemoKeyFixtures;
import com.agenticengineering.urlshortener.tests.support.ScenarioFixtures;
import com.agenticengineering.urlshortener.tests.support.StubUrlTargetResolver;
import java.net.InetAddress;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** T012: scenario, resolver, rate-limit and demo-key fixtures load and are reusable (plan section 11, PVT-003). */
class TestFixturesContextTest {

    @Configuration
    @EnableConfigurationProperties(ShortenerProperties.class)
    static class BindingConfig {
    }

    @Test
    void scenarioRequirementTextsMatchPlanSection11() {
        assertThat(ScenarioFixtures.scnA()).isEqualTo("Link status lookup: a read-only endpoint returning whether a short"
                + " code is active, expired or unknown, with its expiry time (new additive /api/v1 capability; NOT part of"
                + " the MVP)");
        assertThat(ScenarioFixtures.scnB()).isEqualTo("Change the default link TTL from 7 days to 24 hours.");
        assertThat(ScenarioFixtures.scnC()).isEqualTo("Add support for links.");
    }

    @Test
    void stubResolverReturnsProgrammedAddressesAndRejectsUnknownHosts() throws Exception {
        StubUrlTargetResolver resolver = new StubUrlTargetResolver()
                .map("public.test", "203.0.113.10")
                .map("internal.test", "10.0.0.5");

        assertThat(resolver.resolve("public.test")).containsExactly(InetAddress.getByName("203.0.113.10"));
        assertThat(resolver.resolve("internal.test")).containsExactly(InetAddress.getByName("10.0.0.5"));
        assertThatThrownBy(() -> resolver.resolve("unknown.test")).isInstanceOf(UnresolvedHostException.class);
        assertThat(resolver.lookups()).containsExactly("public.test", "internal.test", "unknown.test");
    }

    @Test
    void lowLimitsProfileBindsSmallConfigurableLimits() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=lowlimits")
                .withUserConfiguration(BindingConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ShortenerProperties.RateLimit limits = context.getBean(ShortenerProperties.class).rateLimit();
                    assertThat(limits.creationPerIp().requestsPerMinute()).isEqualTo(3);
                    assertThat(limits.creationPerIp().burst()).isEqualTo(3);
                    assertThat(limits.redirectPerIp().requestsPerMinute()).isEqualTo(5);
                    assertThat(limits.redirectPerIp().burst()).isEqualTo(5);
                });
    }

    @Test
    void demoKeyFixtureHoldsOnlyHashesOfTheTestKeys() {
        assertThat(DemoKeyFixtures.roles()).containsExactly("ENGINEER", "APPROVER", "RELEASE_OWNER", "REVIEWER");
        for (String role : DemoKeyFixtures.roles()) {
            assertThat(DemoKeyFixtures.storedHash(role)).isEqualTo(DemoKeyFixtures.sha256Hex(DemoKeyFixtures.rawKey(role)));
        }
        assertThat(DemoKeyFixtures.fixtureFileText()).doesNotContain(DemoKeyFixtures.rawKey("APPROVER"));
    }
}
