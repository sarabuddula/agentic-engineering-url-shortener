package com.agenticengineering.urlshortener.tests.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticengineering.urlshortener.shared.api.ErrorResponse;
import com.agenticengineering.urlshortener.shared.value.InvalidShortCodeException;
import com.agenticengineering.urlshortener.shared.value.InvalidTargetUrlException;
import com.agenticengineering.urlshortener.shortener.api.GlobalExceptionHandler;
import com.agenticengineering.urlshortener.tests.contract.OpenApiContractHarness;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/** T017: error model and exception mapping match the contract Error schema (FR-002, FR-047, contracts/openapi.yaml). */
class ErrorMappingTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final OpenApiContractHarness CONTRACT = OpenApiContractHarness.load();

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void retryAfterSecondsAppearsOnlyOnRateLimitedBodies() {
        assertThat(ErrorResponse.of("URL_REJECTED", "bad target", "req-1").toBody())
                .containsOnlyKeys("code", "message", "request_id");
        assertThat(ErrorResponse.rateLimited("Too many requests", "req-2", 12).toBody())
                .containsEntry("code", "RATE_LIMITED")
                .containsEntry("retry_after_seconds", 12);
    }

    @Test
    void invalidTargetUrlMapsTo400UrlRejectedWithEchoedRequestId() throws Exception {
        MockHttpServletRequest request = requestWithId("req-abc");

        ResponseEntity<Map<String, Object>> response = handler.handle(
                new InvalidTargetUrlException(InvalidTargetUrlException.Reason.SCHEME_NOT_ALLOWED, "ftp"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).containsEntry("code", "URL_REJECTED").containsEntry("request_id", "req-abc");
        assertThat(CONTRACT.responseViolations("POST", "/api/v1/links", 400, json(response))).isEmpty();
    }

    @Test
    void everyRejectionReasonMapsTo400WithAMessage() {
        for (InvalidTargetUrlException.Reason reason : InvalidTargetUrlException.Reason.values()) {
            ResponseEntity<Map<String, Object>> response =
                    handler.handle(new InvalidTargetUrlException(reason, "detail"), requestWithId("req-" + reason));

            assertThat(response.getStatusCode().value()).as("status for %s", reason).isEqualTo(400);
            assertThat((String) response.getBody().get("message")).as("message for %s", reason).isNotBlank();
        }
    }

    @Test
    void malformedShortCodeMapsTo404LinkNotFound() throws Exception {
        ResponseEntity<Map<String, Object>> response =
                handler.handle(new InvalidShortCodeException("ab-c"), requestWithId("req-404"));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).containsEntry("code", "LINK_NOT_FOUND");
        assertThat(CONTRACT.responseViolations("GET", "/abcd", 404, json(response))).isEmpty();
    }

    @Test
    void rateLimitedBodyConformsToContract() throws Exception {
        String body = JSON.writeValueAsString(ErrorResponse.rateLimited("Too many requests", "req-429", 12).toBody());

        assertThat(CONTRACT.responseViolations("POST", "/api/v1/links", 429, body)).isEmpty();
    }

    @Test
    void unexpectedFailureMapsTo500WithoutLeakingTheExceptionMessage() {
        ResponseEntity<Map<String, Object>> response = handler.handleUnexpected(
                new IllegalStateException("jdbc:h2:file:/secret/path password=hunter2"), requestWithId("req-500"));

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).containsEntry("code", "INTERNAL_ERROR");
        assertThat(String.valueOf(response.getBody())).doesNotContain("hunter2").doesNotContain("/secret/path");
    }

    @Test
    void springRoutingErrorsKeepTheirStatusInsteadOfBecoming500() throws Exception {
        ResponseEntity<Map<String, Object>> notFound = handler.handleFramework(
                new org.springframework.web.servlet.resource.NoResourceFoundException(org.springframework.http.HttpMethod.GET,
                        "api/v1/nope"), requestWithId("req-404b"));
        ResponseEntity<Map<String, Object>> notAllowed = handler.handleFramework(
                new org.springframework.web.HttpRequestMethodNotSupportedException("DELETE"), requestWithId("req-405"));

        assertThat(notFound.getStatusCode().value()).isEqualTo(404);
        assertThat(notFound.getBody()).containsEntry("code", "NOT_FOUND").containsKey("request_id");
        assertThat(notAllowed.getStatusCode().value()).isEqualTo(405);
        assertThat(notAllowed.getBody()).containsEntry("code", "METHOD_NOT_ALLOWED");
    }

    @Test
    void requestIdIsGeneratedWhenTheRequestCarriesNone() {
        ResponseEntity<Map<String, Object>> response = handler.handle(
                new InvalidShortCodeException("x"), new MockHttpServletRequest());

        assertThat((String) response.getBody().get("request_id")).isNotBlank();
    }

    private static MockHttpServletRequest requestWithId(String requestId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(GlobalExceptionHandler.REQUEST_ID_ATTRIBUTE, requestId);
        return request;
    }

    private static String json(ResponseEntity<Map<String, Object>> response) throws Exception {
        return JSON.writeValueAsString(response.getBody());
    }
}
