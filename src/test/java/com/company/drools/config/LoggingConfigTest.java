package com.company.drools.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

@DisplayName("LoggingConfig")
class LoggingConfigTest {

  @Nested
  @DisplayName("Logging Filter")
  class LoggingFilterTests {

    @Test
    @DisplayName("generates correlation ID when not provided")
    void testGeneratesCorrelationId() throws Exception {
      LoggingConfig config = new LoggingConfig();
      OncePerRequestFilter filter = config.loggingFilter();

      MockHttpServletRequest request = new MockHttpServletRequest("GET", "/test");
      MockHttpServletResponse response = new MockHttpServletResponse();

      filter.doFilter(
          request,
          response,
          (req, res) -> {
            assertThat(MDC.get("correlationId")).isNotNull().isNotEmpty();
            assertThat(MDC.get("requestId")).isNotNull().isNotEmpty();
            assertThat(MDC.get("requestUri")).isEqualTo("/test");
            assertThat(MDC.get("requestMethod")).isEqualTo("GET");
          });

      // Verify correlation ID is in response headers
      assertThat(response.getHeader("X-Correlation-ID")).isNotNull();
      assertThat(response.getHeader("X-Request-ID")).isNotNull();

      // Verify MDC is cleaned up
      assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    @DisplayName("uses provided correlation ID from request header")
    void testUsesProvidedCorrelationId() throws Exception {
      LoggingConfig config = new LoggingConfig();
      OncePerRequestFilter filter = config.loggingFilter();

      MockHttpServletRequest request = new MockHttpServletRequest("POST", "/execute-rule");
      request.addHeader("X-Correlation-ID", "test-corr-123");
      request.addHeader("X-Request-ID", "test-req-456");
      MockHttpServletResponse response = new MockHttpServletResponse();

      filter.doFilter(
          request,
          response,
          (req, res) -> {
            assertThat(MDC.get("correlationId")).isEqualTo("test-corr-123");
            assertThat(MDC.get("requestId")).isEqualTo("test-req-456");
          });

      assertThat(response.getHeader("X-Correlation-ID")).isEqualTo("test-corr-123");
      assertThat(response.getHeader("X-Request-ID")).isEqualTo("test-req-456");
    }

    @Test
    @DisplayName("adds user agent to MDC when present")
    void testAddsUserAgent() throws Exception {
      LoggingConfig config = new LoggingConfig();
      OncePerRequestFilter filter = config.loggingFilter();

      MockHttpServletRequest request = new MockHttpServletRequest("GET", "/test");
      request.addHeader("User-Agent", "TestAgent/1.0");
      MockHttpServletResponse response = new MockHttpServletResponse();

      filter.doFilter(
          request,
          response,
          (req, res) -> {
            assertThat(MDC.get("userAgent")).isEqualTo("TestAgent/1.0");
          });
    }

    @Test
    @DisplayName("cleans up MDC even when exception occurs")
    void testCleanupOnException() throws Exception {
      LoggingConfig config = new LoggingConfig();
      OncePerRequestFilter filter = config.loggingFilter();

      MockHttpServletRequest request = new MockHttpServletRequest("GET", "/test");
      MockHttpServletResponse response = new MockHttpServletResponse();

      try {
        filter.doFilter(
            request,
            response,
            (req, res) -> {
              throw new RuntimeException("test error");
            });
      } catch (RuntimeException e) {
        // expected
      }

      // MDC should still be cleaned up
      assertThat(MDC.get("correlationId")).isNull();
      assertThat(MDC.get("requestId")).isNull();
    }
  }

  @Nested
  @DisplayName("StructuredLogging")
  class StructuredLoggingTests {

    @Test
    @DisplayName("addRuleContext adds rule fields to MDC")
    void testAddRuleContext() {
      LoggingConfig.StructuredLogging.addRuleContext("test.rule", "execute");

      assertThat(MDC.get("ruleId")).isEqualTo("test.rule");
      assertThat(MDC.get("operation")).isEqualTo("execute");

      LoggingConfig.StructuredLogging.clearRuleContext();
      assertThat(MDC.get("ruleId")).isNull();
      assertThat(MDC.get("operation")).isNull();
    }

    @Test
    @DisplayName("addCacheContext adds cache fields to MDC")
    void testAddCacheContext() {
      LoggingConfig.StructuredLogging.addCacheContext("local", "get", "test.rule");

      assertThat(MDC.get("cacheType")).isEqualTo("local");
      assertThat(MDC.get("cacheOperation")).isEqualTo("get");
      assertThat(MDC.get("ruleId")).isEqualTo("test.rule");

      LoggingConfig.StructuredLogging.clearCacheContext();
      assertThat(MDC.get("cacheType")).isNull();
      assertThat(MDC.get("cacheOperation")).isNull();
    }

    @Test
    @DisplayName("addStorageContext adds storage fields to MDC")
    void testAddStorageContext() {
      LoggingConfig.StructuredLogging.addStorageContext("s3", "fetch", "test.rule");

      assertThat(MDC.get("storageType")).isEqualTo("s3");
      assertThat(MDC.get("storageOperation")).isEqualTo("fetch");

      LoggingConfig.StructuredLogging.clearStorageContext();
      assertThat(MDC.get("storageType")).isNull();
    }

    @Test
    @DisplayName("addPerformanceContext adds timing fields to MDC")
    void testAddPerformanceContext() {
      LoggingConfig.StructuredLogging.addPerformanceContext(150L, "rule-engine");

      assertThat(MDC.get("executionTimeMs")).isEqualTo("150");
      assertThat(MDC.get("component")).isEqualTo("rule-engine");

      LoggingConfig.StructuredLogging.clearPerformanceContext();
      assertThat(MDC.get("executionTimeMs")).isNull();
    }

    @Test
    @DisplayName("addErrorContext adds error fields to MDC")
    void testAddErrorContext() {
      LoggingConfig.StructuredLogging.addErrorContext("RuntimeException", "ERR001", "engine");

      assertThat(MDC.get("errorType")).isEqualTo("RuntimeException");
      assertThat(MDC.get("errorCode")).isEqualTo("ERR001");
      assertThat(MDC.get("errorComponent")).isEqualTo("engine");

      LoggingConfig.StructuredLogging.clearErrorContext();
      assertThat(MDC.get("errorType")).isNull();
      assertThat(MDC.get("errorCode")).isNull();
      assertThat(MDC.get("errorComponent")).isNull();
    }
  }
}
