package com.agenticengineering.urlshortener.tests.security;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T046 (reduced to the input-rejection matrix, fourth governed change): every target that FR-002, FR-003 and the
 * D-003 rule set reject is refused over HTTP with 400 URL_REJECTED, nothing is stored, and the response never echoes
 * the input (NFR-001). No-secrets-in-logs is covered by T022.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UrlRejectionMatrixIT {

    private static final AtomicInteger KEYS = new AtomicInteger();

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
    }

    @AfterEach
    void nothingWasStored() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM SHORT_LINK", Integer.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        // schemes (A-007)
        "ftp://example.com/f", "javascript:alert(1)", "file:///etc/passwd", "data:text/html,hi", "gopher://example.com/",
        // loopback, unspecified, private, link-local, cloud metadata, unique-local (D-003)
        "http://127.0.0.1/", "http://127.9.9.9/x", "http://0.0.0.0/", "http://10.0.0.1/", "http://172.16.5.4/",
        "http://192.168.0.10/", "http://169.254.169.254/latest/meta-data/", "http://[::1]/", "http://[::]/",
        "http://[fc00::1]/", "http://[fd00:ec2::254]/", "http://[fe80::1]/",
        // IPv4-mapped IPv6 and numeric host forms
        "http://[::ffff:127.0.0.1]/", "http://[::ffff:a9fe:a9fe]/", "http://2130706433/", "http://0177.0.0.1/",
        "http://0x7f000001/", "http://127.1/",
        // localhost names, userinfo, unresolvable hosts, malformed
        "http://localhost/", "http://LocalHost:8080/", "http://admin.localhost/", "https://user:pw@example.com/",
        "https://unlisted.example.net/", "not a url", "https:///no-host"})
    void rejectedWith400AndNotEchoed(String target) {
        Response response = given().contentType(ContentType.JSON).header("Idempotency-Key", "key-matrix-" + KEYS.incrementAndGet())
                .body("{\"target_url\":\"" + target + "\"}").post("/api/v1/links");

        assertThat(response.statusCode()).as("status for %s", target).isEqualTo(400);
        assertThat(response.jsonPath().getString("code")).isEqualTo("URL_REJECTED");
        assertThat(response.asString()).as("input not echoed").doesNotContain(target);
    }

    @ParameterizedTest
    @ValueSource(ints = {2049, 10_000})
    void oversizeTargetsAreRejected(int length) {
        String target = "https://example.com/" + "a".repeat(length - "https://example.com/".length());
        Response response = given().contentType(ContentType.JSON).header("Idempotency-Key", "key-matrix-" + KEYS.incrementAndGet())
                .body("{\"target_url\":\"" + target + "\"}").post("/api/v1/links");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.jsonPath().getString("code")).isEqualTo("URL_REJECTED");
    }
}
