package com.company.drools.api.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Custom Exceptions")
class ExceptionTest {

  @Nested
  @DisplayName("TimeoutException")
  class TimeoutExceptionTest {

    @Test
    @DisplayName("constructor sets operation and timeout")
    void testBasicConstructor() {
      TimeoutException ex = new TimeoutException("rule-execution", 30);

      assertThat(ex.getOperation()).isEqualTo("rule-execution");
      assertThat(ex.getTimeoutSeconds()).isEqualTo(30);
      assertThat(ex.getMessage()).contains("rule-execution");
      assertThat(ex.getMessage()).contains("30");
    }

    @Test
    @DisplayName("constructor with cause preserves cause")
    void testConstructorWithCause() {
      RuntimeException cause = new RuntimeException("underlying error");
      TimeoutException ex = new TimeoutException("storage-fetch", 60, cause);

      assertThat(ex.getOperation()).isEqualTo("storage-fetch");
      assertThat(ex.getTimeoutSeconds()).isEqualTo(60);
      assertThat(ex.getCause()).isEqualTo(cause);
    }
  }

  @Nested
  @DisplayName("CircuitBreakerException")
  class CircuitBreakerExceptionTest {

    @Test
    @DisplayName("constructor sets name and state")
    void testBasicConstructor() {
      CircuitBreakerException ex = new CircuitBreakerException("s3", "OPEN");

      assertThat(ex.getCircuitBreakerName()).isEqualTo("s3");
      assertThat(ex.getState()).isEqualTo("OPEN");
      assertThat(ex.getMessage()).contains("s3");
      assertThat(ex.getMessage()).contains("OPEN");
    }

    @Test
    @DisplayName("constructor with cause preserves cause")
    void testConstructorWithCause() {
      RuntimeException cause = new RuntimeException("connection failed");
      CircuitBreakerException ex = new CircuitBreakerException("redis", "HALF_OPEN", cause);

      assertThat(ex.getCircuitBreakerName()).isEqualTo("redis");
      assertThat(ex.getState()).isEqualTo("HALF_OPEN");
      assertThat(ex.getCause()).isEqualTo(cause);
    }
  }

  @Nested
  @DisplayName("RuleNotFoundException")
  class RuleNotFoundExceptionTest {

    @Test
    @DisplayName("constructor sets rule ID")
    void testConstructor() {
      RuleNotFoundException ex = new RuleNotFoundException("pricing.discount.vip");

      assertThat(ex.getRuleId()).isEqualTo("pricing.discount.vip");
      assertThat(ex.getMessage()).contains("pricing.discount.vip");
    }

    @Test
    @DisplayName("constructor with message")
    void testConstructorWithMessage() {
      RuleNotFoundException ex = new RuleNotFoundException("rule1", "Custom message");
      assertThat(ex.getRuleId()).isEqualTo("rule1");
      assertThat(ex.getMessage()).isEqualTo("Custom message");
    }

    @Test
    @DisplayName("constructor with cause")
    void testConstructorWithCause() {
      RuntimeException cause = new RuntimeException("root cause");
      RuleNotFoundException ex = new RuleNotFoundException("rule1", "msg", cause);
      assertThat(ex.getCause()).isEqualTo(cause);
    }
  }

  @Nested
  @DisplayName("RuleExecutionException")
  class RuleExecutionExceptionTest {

    @Test
    @DisplayName("constructor sets rule ID and message")
    void testBasicConstructor() {
      RuleExecutionException ex = new RuleExecutionException("test.rule", "NPE in rule");

      assertThat(ex.getRuleId()).isEqualTo("test.rule");
      assertThat(ex.getMessage()).contains("NPE in rule");
    }

    @Test
    @DisplayName("constructor with cause")
    void testConstructorWithCause() {
      RuntimeException cause = new RuntimeException("underlying");
      RuleExecutionException ex = new RuleExecutionException("test.rule", "failed", cause);
      assertThat(ex.getCause()).isEqualTo(cause);
      assertThat(ex.getRuleId()).isEqualTo("test.rule");
    }
  }
}
