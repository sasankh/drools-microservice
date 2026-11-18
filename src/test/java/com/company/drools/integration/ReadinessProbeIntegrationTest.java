package com.company.drools.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * Real-server check of the actuator probes: once the startup load has run, {@code
 * /actuator/health/readiness} is UP and includes the {@code rules} indicator (the local profile
 * loads the built-in demo rules from in-memory storage). No Docker required.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "management.server.port=0",
      "management.endpoint.health.show-details=always",
      "management.endpoint.health.show-components=always",
      "redis.enabled=false",
      "management.health.redis.enabled=false"
    })
@ActiveProfiles("local")
@DisplayName("Actuator readiness probe (real server)")
class ReadinessProbeIntegrationTest {

  @LocalManagementPort private int managementPort;

  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private final ObjectMapper mapper = new ObjectMapper();

  private HttpResponse<String> get(String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + managementPort + path))
            .timeout(Duration.ofSeconds(20))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  @DisplayName("readiness is 200 UP and includes the rules indicator with loaded rules")
  void readinessUpWithRulesComponent() throws Exception {
    HttpResponse<String> response = get("/actuator/health/readiness");

    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    JsonNode body = mapper.readTree(response.body());
    assertThat(body.path("status").asText()).isEqualTo("UP");
    JsonNode rules = body.path("components").path("rules");
    assertThat(rules.isMissingNode()).as("rules component in " + response.body()).isFalse();
    assertThat(rules.path("status").asText()).isEqualTo("UP");
    assertThat(rules.path("details").path("phase").asText()).isEqualTo("LOADED");
    assertThat(rules.path("details").path("loaded_rules").asInt()).isPositive();
    assertThat(rules.path("details").path("last_refresh_source").asText()).isEqualTo("startup");
    assertThat(body.path("components").path("readinessState").path("status").asText())
        .isEqualTo("UP");
  }

  @Test
  @DisplayName("liveness is 200 UP and does not depend on the rules indicator")
  void livenessUp() throws Exception {
    HttpResponse<String> response = get("/actuator/health/liveness");

    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    JsonNode body = mapper.readTree(response.body());
    assertThat(body.path("status").asText()).isEqualTo("UP");
    assertThat(body.path("components").has("rules")).isFalse();
  }
}
