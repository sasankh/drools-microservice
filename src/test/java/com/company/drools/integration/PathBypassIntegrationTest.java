package com.company.drools.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * Regression guard for the admin-auth path bypass found on 2026-11-17 (ADR-024): {@code
 * /admin;x/health} and {@code /%61dmin/health} reached the admin controller without an API key
 * because the filter matched the raw URI while Spring MVC routed the decoded path.
 *
 * <p>Uses a real embedded Tomcat (MockMvc does not reproduce raw-URI handling) and {@link
 * HttpClient} with {@link URI#create} so paths are sent exactly as written. No Docker required.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "drools.admin.api-key=" + PathBypassIntegrationTest.KEY,
      "management.server.port=0",
      "redis.enabled=false",
      "management.health.redis.enabled=false"
    })
@ActiveProfiles("local")
@DisplayName("Path bypass (real Tomcat)")
class PathBypassIntegrationTest {

  static final String KEY = "path-bypass-test-key";

  @LocalServerPort private int port;

  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  private HttpResponse<String> send(String method, String rawPath, String apiKey, String body)
      throws Exception {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + rawPath))
            .timeout(Duration.ofSeconds(20));
    if (apiKey != null) {
      b.header("X-Admin-API-Key", apiKey);
    }
    if ("POST".equals(method)) {
      b.header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body));
    } else {
      b.GET();
    }
    return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  @ParameterizedTest(name = "{0} {1} without key -> 400")
  @CsvSource({
    "GET, /admin;x/health",
    "GET, /%61dmin/health",
    "GET, /admin/health;x",
    "GET, /%61dmin/rules",
    "POST, /%61dmin/refresh-rules",
    "POST, /admin;x/memory/gc",
    "POST, /%65xecute-rule"
  })
  void testAmbiguousPathsRejected(String method, String path) throws Exception {
    HttpResponse<String> response =
        send(method, path, null, "{\"rule_id\":\"pricing.discount.simple\",\"data\":{}}");

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).contains("INVALID_INPUT").doesNotContain("\"status\":\"UP\"");
    // SecurityHeadersFilter runs first, so the rejection still carries the security headers.
    assertThat(response.headers().firstValue("X-Frame-Options")).hasValue("DENY");
    assertThat(response.headers().firstValue("Content-Security-Policy")).isPresent();
  }

  @Test
  @DisplayName("/admin/health without key -> 401, wrong key -> 401, correct key -> 200")
  void testAdminAuth() throws Exception {
    assertThat(send("GET", "/admin/health", null, null).statusCode()).isEqualTo(401);
    assertThat(send("GET", "/admin/health", "wrong", null).statusCode()).isEqualTo(401);

    HttpResponse<String> ok = send("GET", "/admin/health", KEY, null);
    assertThat(ok.statusCode()).isEqualTo(200);
    assertThat(ok.body()).contains("\"components\"");
  }

  @Test
  @DisplayName("/execute-rule still works")
  void testExecuteRule() throws Exception {
    HttpResponse<String> response =
        send(
            "POST",
            "/execute-rule",
            null,
            "{\"rule_id\":\"pricing.discount.simple\",\"data\":{\"amount\":100}}");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"error\":null");
  }
}
