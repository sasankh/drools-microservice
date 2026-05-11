package com.company.drools.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("DTOs")
class DtoTest {

  @Nested
  @DisplayName("ErrorResponse")
  class ErrorResponseTest {

    @Test
    @DisplayName("default constructor sets timestamp")
    void testDefaultConstructor() {
      ErrorResponse error = new ErrorResponse();
      assertThat(error.getTimestamp()).isNotNull();
      assertThat(error.getCode()).isNull();
      assertThat(error.getMessage()).isNull();
      assertThat(error.getDetails()).isNull();
    }

    @Test
    @DisplayName("parameterized constructor sets all fields")
    void testParameterizedConstructor() {
      ErrorResponse error = new ErrorResponse("ERR001", "Something failed", "Check input");
      assertThat(error.getCode()).isEqualTo("ERR001");
      assertThat(error.getMessage()).isEqualTo("Something failed");
      assertThat(error.getDetails()).isEqualTo("Check input");
      assertThat(error.getTimestamp()).isNotNull();
    }

    @Test
    @DisplayName("setters update fields")
    void testSetters() {
      ErrorResponse error = new ErrorResponse();
      error.setCode("ERR002");
      error.setMessage("Updated message");
      error.setDetails("Updated details");
      error.setTimestamp("2024-01-01T00:00:00Z");

      assertThat(error.getCode()).isEqualTo("ERR002");
      assertThat(error.getMessage()).isEqualTo("Updated message");
      assertThat(error.getDetails()).isEqualTo("Updated details");
      assertThat(error.getTimestamp()).isEqualTo("2024-01-01T00:00:00Z");
    }

    @Test
    @DisplayName("equals and hashCode work correctly")
    void testEqualsAndHashCode() {
      ErrorResponse e1 = new ErrorResponse("ERR", "msg", "det");
      ErrorResponse e2 = new ErrorResponse("ERR", "msg", "det");
      ErrorResponse e3 = new ErrorResponse("ERR2", "msg", "det");

      assertThat(e1)
          .isEqualTo(e2)
          .isNotEqualTo(e3)
          .isNotEqualTo(null)
          .isNotEqualTo("string")
          .isEqualTo(e1)
          .hasSameHashCodeAs(e2);
    }

    @Test
    @DisplayName("toString contains all fields")
    void testToString() {
      ErrorResponse error = new ErrorResponse("ERR", "msg", "det");
      String str = error.toString();
      assertThat(str).contains("ERR").contains("msg").contains("det");
    }
  }

  @Nested
  @DisplayName("RuleExecutionRequest")
  class RuleExecutionRequestTest {

    @Test
    @DisplayName("default constructor creates empty request")
    void testDefaultConstructor() {
      RuleExecutionRequest request = new RuleExecutionRequest();
      assertThat(request.getRuleId()).isNull();
      assertThat(request.getData()).isNull();
    }

    @Test
    @DisplayName("parameterized constructor sets fields")
    void testParameterizedConstructor() {
      Map<String, Object> data = Map.of("amount", 100.0);
      RuleExecutionRequest request = new RuleExecutionRequest("pricing.discount.simple", data);

      assertThat(request.getRuleId()).isEqualTo("pricing.discount.simple");
      assertThat(request.getData()).containsEntry("amount", 100.0);
    }

    @Test
    @DisplayName("setters update fields")
    void testSetters() {
      RuleExecutionRequest request = new RuleExecutionRequest();
      request.setRuleId("test.rule");
      request.setData(Map.of("key", "value"));

      assertThat(request.getRuleId()).isEqualTo("test.rule");
      assertThat(request.getData()).containsEntry("key", "value");
    }

    @Test
    @DisplayName("equals and hashCode work correctly")
    void testEqualsAndHashCode() {
      Map<String, Object> data = Map.of("a", 1);
      RuleExecutionRequest r1 = new RuleExecutionRequest("rule1", data);
      RuleExecutionRequest r2 = new RuleExecutionRequest("rule1", data);
      RuleExecutionRequest r3 = new RuleExecutionRequest("rule2", data);

      assertThat(r1)
          .isEqualTo(r2)
          .isNotEqualTo(r3)
          .isNotEqualTo(null)
          .isNotEqualTo("string")
          .isEqualTo(r1)
          .hasSameHashCodeAs(r2);
    }

    @Test
    @DisplayName("toString contains fields")
    void testToString() {
      RuleExecutionRequest request = new RuleExecutionRequest("test.rule", Map.of("a", 1));
      assertThat(request.toString()).contains("test.rule");
    }
  }

  @Nested
  @DisplayName("RuleExecutionResponse")
  class RuleExecutionResponseTest {

    @Test
    @DisplayName("default constructor creates empty response")
    void testDefaultConstructor() {
      RuleExecutionResponse response = new RuleExecutionResponse();
      assertThat(response.getRuleId()).isNull();
      assertThat(response.getResult()).isNull();
      assertThat(response.getError()).isNull();
      assertThat(response.getExecutionTimeMs()).isNull();
    }

    @Test
    @DisplayName("success factory method creates success response")
    void testSuccessFactory() {
      Map<String, Object> result = Map.of("amount", 90.0);
      RuleExecutionResponse response = RuleExecutionResponse.success("rule1", result, 15L);

      assertThat(response.getRuleId()).isEqualTo("rule1");
      assertThat(response.getResult()).containsEntry("amount", 90.0);
      assertThat(response.getError()).isNull();
      assertThat(response.getExecutionTimeMs()).isEqualTo(15L);
    }

    @Test
    @DisplayName("failure factory method creates failure response")
    void testFailureFactory() {
      RuleExecutionResponse response =
          RuleExecutionResponse.failure("rule1", "ERR", "error message");

      assertThat(response.getRuleId()).isEqualTo("rule1");
      assertThat(response.getResult()).isNull();
      assertThat(response.getError()).isNotNull();
      assertThat(response.getError().getCode()).isEqualTo("ERR");
      assertThat(response.getError().getMessage()).isEqualTo("error message");
    }

    @Test
    @DisplayName("failure with details factory method")
    void testFailureWithDetailsFactory() {
      RuleExecutionResponse response =
          RuleExecutionResponse.failure("rule1", "ERR", "message", "details");

      assertThat(response.getError().getDetails()).isEqualTo("details");
    }

    @Test
    @DisplayName("setters update fields")
    void testSetters() {
      RuleExecutionResponse response = new RuleExecutionResponse();
      response.setRuleId("test");
      response.setResult(Map.of("key", "val"));
      response.setError(new ErrorResponse("E", "m", "d"));
      response.setExecutionTimeMs(42L);

      assertThat(response.getRuleId()).isEqualTo("test");
      assertThat(response.getResult()).containsKey("key");
      assertThat(response.getError().getCode()).isEqualTo("E");
      assertThat(response.getExecutionTimeMs()).isEqualTo(42L);
    }

    @Test
    @DisplayName("equals and hashCode work correctly")
    void testEqualsAndHashCode() {
      Map<String, Object> result = Map.of("a", 1);
      RuleExecutionResponse r1 = new RuleExecutionResponse("rule1", result, null, 10L);
      RuleExecutionResponse r2 = new RuleExecutionResponse("rule1", result, null, 10L);
      RuleExecutionResponse r3 = new RuleExecutionResponse("rule2", result, null, 10L);

      assertThat(r1)
          .isEqualTo(r2)
          .isNotEqualTo(r3)
          .isNotEqualTo(null)
          .isNotEqualTo("string")
          .isEqualTo(r1)
          .hasSameHashCodeAs(r2);
    }

    @Test
    @DisplayName("toString contains fields")
    void testToString() {
      RuleExecutionResponse response =
          RuleExecutionResponse.success("test.rule", Map.of("a", 1), 5L);
      assertThat(response.toString()).contains("test.rule");
    }
  }

  @Nested
  @DisplayName("RuleListResponse")
  class RuleListResponseTest {

    @Test
    @DisplayName("default constructor sets timestamp")
    void testDefaultConstructor() {
      RuleListResponse response = new RuleListResponse();
      assertThat(response.getTimestamp()).isNotNull();
      assertThat(response.getTotalRules()).isZero();
    }

    @Test
    @DisplayName("constructor with rules sets total")
    void testConstructorWithRules() {
      List<RuleListResponse.RuleInfo> rules =
          List.of(
              new RuleListResponse.RuleInfo(
                  "rule1", "ACTIVE", Instant.now(), Instant.now(), 10, 5.0, true, "1.0"),
              new RuleListResponse.RuleInfo(
                  "rule2", "ACTIVE", Instant.now(), Instant.now(), 5, 3.0, false, "1.1"));

      RuleListResponse response = new RuleListResponse(rules);
      assertThat(response.getTotalRules()).isEqualTo(2);
      assertThat(response.getRules()).hasSize(2);
    }

    @Test
    @DisplayName("setRules updates totalRules count")
    void testSetRulesUpdatesTotalCount() {
      RuleListResponse response = new RuleListResponse();
      response.setRules(List.of());
      assertThat(response.getTotalRules()).isZero();

      response.setRules(null);
      assertThat(response.getTotalRules()).isZero();
    }

    @Test
    @DisplayName("setters update fields")
    void testSetters() {
      RuleListResponse response = new RuleListResponse();
      Instant now = Instant.now();
      response.setTotalRules(5);
      response.setTimestamp(now);

      assertThat(response.getTotalRules()).isEqualTo(5);
      assertThat(response.getTimestamp()).isEqualTo(now);
    }

    @Test
    @DisplayName("constructor with null rules")
    void testConstructorWithNullRules() {
      RuleListResponse response = new RuleListResponse(null);
      assertThat(response.getTotalRules()).isZero();
    }
  }

  @Nested
  @DisplayName("RuleListResponse.RuleInfo")
  class RuleInfoTest {

    @Test
    @DisplayName("default constructor creates empty rule info")
    void testDefaultConstructor() {
      RuleListResponse.RuleInfo info = new RuleListResponse.RuleInfo();
      assertThat(info.getRuleId()).isNull();
      assertThat(info.getStatus()).isNull();
    }

    @Test
    @DisplayName("full constructor sets all fields")
    void testFullConstructor() {
      Instant now = Instant.now();
      RuleListResponse.RuleInfo info =
          new RuleListResponse.RuleInfo("rule1", "ACTIVE", now, now, 100, 5.5, true, "2.0");

      assertThat(info.getRuleId()).isEqualTo("rule1");
      assertThat(info.getStatus()).isEqualTo("ACTIVE");
      assertThat(info.getLoadedAt()).isEqualTo(now);
      assertThat(info.getLastModified()).isEqualTo(now);
      assertThat(info.getExecutionCount()).isEqualTo(100);
      assertThat(info.getAvgExecutionTimeMs()).isEqualTo(5.5);
      assertThat(info.isCached()).isTrue();
      assertThat(info.getVersion()).isEqualTo("2.0");
    }

    @Test
    @DisplayName("setters update all fields")
    void testSetters() {
      RuleListResponse.RuleInfo info = new RuleListResponse.RuleInfo();
      Instant now = Instant.now();

      info.setRuleId("r1");
      info.setStatus("INACTIVE");
      info.setLoadedAt(now);
      info.setLastModified(now);
      info.setExecutionCount(50);
      info.setAvgExecutionTimeMs(3.3);
      info.setCached(false);
      info.setVersion("1.0");

      assertThat(info.getRuleId()).isEqualTo("r1");
      assertThat(info.getStatus()).isEqualTo("INACTIVE");
      assertThat(info.getExecutionCount()).isEqualTo(50);
      assertThat(info.isCached()).isFalse();
    }
  }

  @Nested
  @DisplayName("RefreshRulesResponse")
  class RefreshRulesResponseTest {

    @Test
    @DisplayName("default constructor sets timestamp")
    void testDefaultConstructor() {
      RefreshRulesResponse response = new RefreshRulesResponse();
      assertThat(response.getTimestamp()).isNotNull();
    }

    @Test
    @DisplayName("full constructor sets all fields")
    void testFullConstructor() {
      List<RefreshRulesResponse.RuleError> errors =
          List.of(new RefreshRulesResponse.RuleError("rule1", "compilation error"));

      RefreshRulesResponse response = new RefreshRulesResponse("PARTIAL", 9, 1, 250L, errors);

      assertThat(response.getStatus()).isEqualTo("PARTIAL");
      assertThat(response.getRulesLoaded()).isEqualTo(9);
      assertThat(response.getRulesFailed()).isEqualTo(1);
      assertThat(response.getDurationMs()).isEqualTo(250L);
      assertThat(response.getErrors()).hasSize(1);
      assertThat(response.getCacheUpdatedAt()).isNotNull();
      assertThat(response.getTimestamp()).isNotNull();
    }

    @Test
    @DisplayName("setters update all fields")
    void testSetters() {
      RefreshRulesResponse response = new RefreshRulesResponse();
      Instant now = Instant.now();

      response.setStatus("SUCCESS");
      response.setRulesLoaded(10);
      response.setRulesFailed(0);
      response.setDurationMs(100L);
      response.setErrors(List.of());
      response.setCacheUpdatedAt(now);
      response.setTimestamp(now);

      assertThat(response.getStatus()).isEqualTo("SUCCESS");
      assertThat(response.getRulesLoaded()).isEqualTo(10);
      assertThat(response.getRulesFailed()).isZero();
      assertThat(response.getDurationMs()).isEqualTo(100L);
      assertThat(response.getErrors()).isEmpty();
      assertThat(response.getCacheUpdatedAt()).isEqualTo(now);
      assertThat(response.getTimestamp()).isEqualTo(now);
    }
  }

  @Nested
  @DisplayName("RefreshRulesResponse.RuleError")
  class RuleErrorTest {

    @Test
    @DisplayName("default constructor creates empty error")
    void testDefaultConstructor() {
      RefreshRulesResponse.RuleError error = new RefreshRulesResponse.RuleError();
      assertThat(error.getRuleId()).isNull();
      assertThat(error.getError()).isNull();
    }

    @Test
    @DisplayName("parameterized constructor sets fields")
    void testParameterizedConstructor() {
      RefreshRulesResponse.RuleError error =
          new RefreshRulesResponse.RuleError("rule1", "compile error");
      assertThat(error.getRuleId()).isEqualTo("rule1");
      assertThat(error.getError()).isEqualTo("compile error");
    }

    @Test
    @DisplayName("setters update fields")
    void testSetters() {
      RefreshRulesResponse.RuleError error = new RefreshRulesResponse.RuleError();
      error.setRuleId("r1");
      error.setError("err");
      assertThat(error.getRuleId()).isEqualTo("r1");
      assertThat(error.getError()).isEqualTo("err");
    }
  }

  @Nested
  @DisplayName("HealthCheckResponse")
  class HealthCheckResponseTest {

    @Test
    @DisplayName("default constructor sets timestamp")
    void testDefaultConstructor() {
      HealthCheckResponse response = new HealthCheckResponse();
      assertThat(response.getTimestamp()).isNotNull();
    }

    @Test
    @DisplayName("parameterized constructor sets all fields")
    void testParameterizedConstructor() {
      Map<String, HealthCheckResponse.ComponentHealth> components = new HashMap<>();
      components.put("drools", new HealthCheckResponse.ComponentHealth("UP", Map.of("rules", 10)));

      HealthCheckResponse response = new HealthCheckResponse("UP", components);
      assertThat(response.getStatus()).isEqualTo("UP");
      assertThat(response.getComponents()).containsKey("drools");
    }

    @Test
    @DisplayName("setters update fields")
    void testSetters() {
      HealthCheckResponse response = new HealthCheckResponse();
      Instant now = Instant.now();
      response.setStatus("DOWN");
      response.setTimestamp(now);
      response.setComponents(Map.of());

      assertThat(response.getStatus()).isEqualTo("DOWN");
      assertThat(response.getTimestamp()).isEqualTo(now);
      assertThat(response.getComponents()).isEmpty();
    }
  }

  @Nested
  @DisplayName("HealthCheckResponse.ComponentHealth")
  class ComponentHealthTest {

    @Test
    @DisplayName("default constructor creates empty component health")
    void testDefaultConstructor() {
      HealthCheckResponse.ComponentHealth health = new HealthCheckResponse.ComponentHealth();
      assertThat(health.getStatus()).isNull();
      assertThat(health.getDetails()).isNull();
    }

    @Test
    @DisplayName("parameterized constructor sets fields")
    void testParameterizedConstructor() {
      Map<String, Object> details = Map.of("version", "8.44");
      HealthCheckResponse.ComponentHealth health =
          new HealthCheckResponse.ComponentHealth("UP", details);
      assertThat(health.getStatus()).isEqualTo("UP");
      assertThat(health.getDetails()).containsEntry("version", "8.44");
    }

    @Test
    @DisplayName("setters update fields")
    void testSetters() {
      HealthCheckResponse.ComponentHealth health = new HealthCheckResponse.ComponentHealth();
      health.setStatus("DOWN");
      health.setDetails(Map.of("error", "connection refused"));
      assertThat(health.getStatus()).isEqualTo("DOWN");
      assertThat(health.getDetails()).containsKey("error");
    }
  }
}
