package com.agenticengineering.urlshortener.tests.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.agenticengineering.urlshortener.shared.value.InvalidShortCodeException;
import com.agenticengineering.urlshortener.shortener.api.RequestCorrelationFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * T022: every request carries a correlation id in the logging context (NFR-005); logs show the key ID only, never key
 * material, headers or query strings (NFR-001, plan section 7).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class RequestCorrelationLoggingTest {

    private static final String RAW_KEY = "raw-approver-key-must-never-be-logged";
    private static final Pattern ACCESS_LINE = Pattern.compile("request_id=(req-[0-9a-f-]{36})");

    @Autowired
    private MockMvc mvc;

    @TestConfiguration
    static class TestEndpoints {

        /** Stands in for authentication (T061): resolves the key and records only its ID on the request. */
        @Bean
        FilterRegistrationBean<Filter> fakeKeyResolution() {
            FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>((request, response, chain) -> {
                request.setAttribute(RequestCorrelationFilter.KEY_ID_ATTRIBUTE, "demo-approver");
                chain.doFilter(request, response);
            });
            registration.setOrder(Ordered.LOWEST_PRECEDENCE);
            return registration;
        }

        @Bean
        Probe probe() {
            return new Probe();
        }
    }

    @RestController
    static class Probe {

        @GetMapping("/test/ok")
        String ok() {
            return "ok";
        }

        @GetMapping("/test/fail")
        String fail() {
            throw new InvalidShortCodeException("x");
        }
    }

    @Test
    void accessLogCarriesRequestIdAndKeyIdButNoKeyMaterial(CapturedOutput output) throws Exception {
        mvc.perform(get("/test/ok").header("X-API-Key", RAW_KEY));

        assertThat(output.getOut()).contains("method=GET path=/test/ok status=200").contains("key_id=demo-approver");
        assertThat(ACCESS_LINE.matcher(output.getOut()).find()).isTrue();
        assertThat(output.getAll()).doesNotContain(RAW_KEY);
    }

    @Test
    void errorBodyEchoesTheLoggedRequestId(CapturedOutput output) throws Exception {
        MvcResult result = mvc.perform(get("/test/fail")).andReturn();

        String bodyRequestId = new ObjectMapper().readTree(result.getResponse().getContentAsString()).get("request_id").asText();
        Matcher logged = ACCESS_LINE.matcher(output.getOut());
        assertThat(logged.find()).isTrue();
        assertThat(bodyRequestId).isEqualTo(logged.group(1));
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void queryStringsAreNotLogged(CapturedOutput output) throws Exception {
        mvc.perform(get("/test/ok").queryParam("token", "query-secret-value"));

        assertThat(output.getAll()).contains("path=/test/ok").doesNotContain("query-secret-value");
    }

    @Test
    void loggingContextIsClearedAfterTheRequest() throws Exception {
        mvc.perform(get("/test/ok"));

        assertThat(MDC.get(RequestCorrelationFilter.MDC_REQUEST_ID)).isNull();
    }
}
