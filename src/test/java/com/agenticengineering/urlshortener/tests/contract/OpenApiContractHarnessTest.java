package com.agenticengineering.urlshortener.tests.contract;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * T011: the contract-test harness loads {@code contracts/openapi.yaml} with the approved validator
 * (swagger-request-validator 2.44.8, research.md row 9) and the hand-written fallback assertions agree with it.
 */
class OpenApiContractHarnessTest {

    private static final String HEALTH_UP = "{\"status\":\"UP\",\"components\":{\"db\":{\"status\":\"UP\"}}}";
    private static final String HEALTH_INVALID = "{\"status\":\"MAYBE\"}";

    private final OpenApiContractHarness harness = OpenApiContractHarness.load();

    @Test
    void contractParsesWithoutErrors() {
        assertThat(harness.specificationPath()).isRegularFile();
        assertThat(harness.validator()).isNotNull();
    }

    @Test
    void validHealthResponseConformsToContract() {
        assertThat(harness.responseViolations("GET", "/actuator/health", 200, HEALTH_UP)).isEmpty();
    }

    @Test
    void invalidHealthResponseIsRejectedByContract() {
        assertThat(harness.responseViolations("GET", "/actuator/health", 200, HEALTH_INVALID)).isNotEmpty();
    }

    @Test
    void fallbackAssertionsAgreeWithValidator() {
        assertThat(ContractFallbackAssertions.healthStatusViolations(HEALTH_UP)).isEmpty();
        assertThat(ContractFallbackAssertions.healthStatusViolations(HEALTH_INVALID)).isNotEmpty();
    }
}
