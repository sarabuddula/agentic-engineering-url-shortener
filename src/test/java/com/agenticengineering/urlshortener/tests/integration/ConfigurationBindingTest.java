package com.agenticengineering.urlshortener.tests.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticengineering.urlshortener.orchestration.config.OrchestrationProperties;
import com.agenticengineering.urlshortener.shared.dns.ResolverMode;
import com.agenticengineering.urlshortener.shortener.config.ShortenerProperties;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** T008: application.yml binds every externalized limit, TTL and timeout (A-006, A-008, A-013 to A-018 bounds). */
class ConfigurationBindingTest {

    @Configuration
    @EnableConfigurationProperties({ShortenerProperties.class, OrchestrationProperties.class})
    static class BindingConfig {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class))
            .withUserConfiguration(BindingConfig.class);

    @Test
    void contextLoadsWithBoundApplicationPlaneProperties() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            ShortenerProperties app = context.getBean(ShortenerProperties.class);

            assertThat(app.dns().resolverMode()).isEqualTo(ResolverMode.FIXED_LOCAL);
            assertThat(app.dns().lookupTimeout()).isEqualTo(Duration.ofSeconds(3));
            assertThat(app.rateLimit().creationPerIp().requestsPerMinute()).isEqualTo(60);
            assertThat(app.rateLimit().creationPerIp().burst()).isEqualTo(100);
            assertThat(app.rateLimit().redirectPerIp().requestsPerMinute()).isEqualTo(600);
            assertThat(app.rateLimit().redirectPerIp().burst()).isEqualTo(200);
            assertThat(app.defaultTtlDays()).isEqualTo(7);
        });
    }

    @Test
    void contextLoadsWithBoundControlPlaneProperties() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            OrchestrationProperties orchestration = context.getBean(OrchestrationProperties.class);

            assertThat(orchestration.approvalTimeoutMinutes()).isEqualTo(5);
            assertThat(orchestration.reworkCap()).isEqualTo(2);
            assertThat(orchestration.replanCap()).isEqualTo(2);
            assertThat(orchestration.syncWaitApprovalWindows()).isEqualTo(2);
            assertThat(orchestration.rateLimit().controlPlanePerKey().requestsPerMinute()).isEqualTo(120);
            assertThat(orchestration.rateLimit().failedAuthPerIp().failuresPerMinute()).isEqualTo(10);
            assertThat(orchestration.rateLimit().failedAuthPerIp().blockDuration()).isEqualTo(Duration.ofMinutes(5));
        });
    }

    @Test
    void dataJpaContextHasADatasource() {
        runner.run(context -> assertThat(context).hasSingleBean(DataSource.class));
    }
}
