package com.agenticengineering.urlshortener.tests.contract;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;
import com.atlassian.oai.validator.restassured.OpenApiValidationFilter;
import java.nio.file.Path;
import java.util.List;

/**
 * Contract-test harness over {@code contracts/openapi.yaml} (plan section 10, API contract category). Uses the approved
 * validator; {@link ContractFallbackAssertions} is the recorded fallback if the validator cannot be used.
 */
public final class OpenApiContractHarness {

    public static final Path SPECIFICATION = Path.of("specs/001-agentic-url-shortener/contracts/openapi.yaml");

    private final OpenApiInteractionValidator validator;

    private OpenApiContractHarness(OpenApiInteractionValidator validator) {
        this.validator = validator;
    }

    /** Loads and parses the contract; a parse error fails with {@link OpenApiInteractionValidator.ApiLoadException}. */
    public static OpenApiContractHarness load() {
        String location = SPECIFICATION.toAbsolutePath().toString();
        return new OpenApiContractHarness(OpenApiInteractionValidator.createForSpecificationUrl(location).build());
    }

    public Path specificationPath() {
        return SPECIFICATION;
    }

    public OpenApiInteractionValidator validator() {
        return validator;
    }

    /** REST Assured filter that validates every request and response of an API contract test against the contract. */
    public OpenApiValidationFilter restAssuredFilter() {
        return new OpenApiValidationFilter(validator);
    }

    /** Validates one JSON response; returns the violation messages (empty when the response conforms). */
    public List<String> responseViolations(String method, String path, int status, String jsonBody) {
        SimpleResponse response = SimpleResponse.Builder.status(status)
                .withContentType("application/json")
                .withBody(jsonBody)
                .build();
        ValidationReport report = validator.validateResponse(path, Request.Method.valueOf(method), response);
        return report.getMessages().stream().map(ValidationReport.Message::getMessage).toList();
    }
}
