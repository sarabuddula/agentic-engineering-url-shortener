package com.agenticengineering.urlshortener.tests.integration;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * SCN-C acceptance (plan section 11; REPLAY run run-05c9fd8d-0888-4b45-a1ae-4516f00688c4, task t4): the clarified
 * requirement "links = the existing URL-shortener short links; support = create (POST /api/v1/links) and resolve
 * (GET /{code} redirect)" holds over HTTP. Named *Test so the N09 default pattern executes it; the same behaviour is
 * covered in more depth by LinkCreationIT, RedirectIT and AppPlaneE2EIT, which N09 does not run.
 * Requirements: SCN-C, FR-042, FR-001, FR-005.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScnCCreateResolveTest {

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
        jdbc.update("DELETE FROM SHORT_LINK");
    }

    @Test
    void aCreatedShortLinkResolvesToItsTarget() {
        Response created = given().contentType(ContentType.JSON).header("Idempotency-Key", "scn-c-create-resolve-01")
                .body("{\"target_url\":\"https://example.com/scn-c\"}").post("/api/v1/links");
        assertThat(created.statusCode()).isEqualTo(201);
        String code = created.jsonPath().getString("short_code");
        assertThat(code).isNotBlank();

        Response resolved = given().redirects().follow(false).get("/" + code);
        assertThat(resolved.statusCode()).isEqualTo(302);
        assertThat(resolved.header("Location")).isEqualTo("https://example.com/scn-c");
    }

    @Test
    void anUnknownCodeDoesNotResolve() {
        assertThat(given().redirects().follow(false).get("/scnc0000").statusCode()).isEqualTo(404);
    }
}
