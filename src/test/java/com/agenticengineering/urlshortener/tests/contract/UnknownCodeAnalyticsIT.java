package com.agenticengineering.urlshortener.tests.contract;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.restassured.RestAssured;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/** T108: a GET of an unknown code records a NOT_FOUND event without a link, and the summary counts it (FR-012, FR-014). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UnknownCodeAnalyticsIT {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM REDIRECT_EVENT");
    }

    @Test
    void unknownCodeIsRecordedAndCounted() {
        given().redirects().follow(false).get("/unkn0wn1").then().statusCode(404);

        Map<String, Object> event = jdbc.queryForMap("SELECT SHORT_LINK_ID, ATTEMPTED_CODE, OUTCOME FROM REDIRECT_EVENT");
        assertThat(event).containsEntry("OUTCOME", "NOT_FOUND").containsEntry("ATTEMPTED_CODE", "unkn0wn1");
        assertThat(event.get("SHORT_LINK_ID")).isNull();
        assertThat(given().queryParam("code", "unkn0wn1").get("/api/v1/analytics/summary").jsonPath().getInt("not_found"))
                .isEqualTo(1);
    }
}
