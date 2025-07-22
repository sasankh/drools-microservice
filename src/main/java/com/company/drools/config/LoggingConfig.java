package com.company.drools.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Configuration for structured logging with request correlation. Adds correlation IDs, request
 * tracking, and structured fields to logs.
 */
@Configuration
public class LoggingConfig {

  private static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
  private static final String REQUEST_ID_HEADER = "X-Request-ID";
  private static final String CORRELATION_ID_KEY = "correlationId";
  private static final String REQUEST_ID_KEY = "requestId";
  private static final String REQUEST_URI_KEY = "requestUri";
  private static final String REQUEST_METHOD_KEY = "requestMethod";
  private static final String USER_AGENT_KEY = "userAgent";

  /** Filter to add correlation IDs and request tracking to MDC for structured logging. */
  @Bean
  public OncePerRequestFilter loggingFilter() {
    return new OncePerRequestFilter() {
      @Override
      protected void doFilterInternal(
          HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
          throws ServletException, IOException {

        try {
          // Get or generate correlation ID
          String correlationId = request.getHeader(CORRELATION_ID_HEADER);
          if (correlationId == null || correlationId.isEmpty()) {
            correlationId = UUID.randomUUID().toString();
          }

          // Get or generate request ID
          String requestId = request.getHeader(REQUEST_ID_HEADER);
          if (requestId == null || requestId.isEmpty()) {
            requestId = UUID.randomUUID().toString();
          }

          // Add to MDC for structured logging
          MDC.put(CORRELATION_ID_KEY, correlationId);
          MDC.put(REQUEST_ID_KEY, requestId);
          MDC.put(REQUEST_URI_KEY, request.getRequestURI());
          MDC.put(REQUEST_METHOD_KEY, request.getMethod());

          // Add user agent if available
          String userAgent = request.getHeader("User-Agent");
          if (userAgent != null) {
            MDC.put(USER_AGENT_KEY, userAgent);
          }

          // Add correlation ID to response header
          response.setHeader(CORRELATION_ID_HEADER, correlationId);
          response.setHeader(REQUEST_ID_HEADER, requestId);

          filterChain.doFilter(request, response);

        } finally {
          // Clean up MDC to prevent memory leaks
          MDC.clear();
        }
      }
    };
  }

  /** Utility class for adding structured logging fields. */
  public static class StructuredLogging {

    /** Add rule execution context to MDC. */
    public static void addRuleContext(String ruleId, String operation) {
      MDC.put("ruleId", ruleId);
      MDC.put("operation", operation);
    }

    /** Add cache operation context to MDC. */
    public static void addCacheContext(String cacheType, String operation, String ruleId) {
      MDC.put("cacheType", cacheType);
      MDC.put("cacheOperation", operation);
      MDC.put("ruleId", ruleId);
    }

    /** Add storage operation context to MDC. */
    public static void addStorageContext(String storageType, String operation, String ruleId) {
      MDC.put("storageType", storageType);
      MDC.put("storageOperation", operation);
      MDC.put("ruleId", ruleId);
    }

    /** Add performance metrics to MDC. */
    public static void addPerformanceContext(long executionTime, String component) {
      MDC.put("executionTimeMs", String.valueOf(executionTime));
      MDC.put("component", component);
    }

    /** Add error context to MDC. */
    public static void addErrorContext(String errorType, String errorCode, String component) {
      MDC.put("errorType", errorType);
      MDC.put("errorCode", errorCode);
      MDC.put("errorComponent", component);
    }

    /** Remove rule execution context from MDC. */
    public static void clearRuleContext() {
      MDC.remove("ruleId");
      MDC.remove("operation");
    }

    /** Remove cache context from MDC. */
    public static void clearCacheContext() {
      MDC.remove("cacheType");
      MDC.remove("cacheOperation");
    }

    /** Remove storage context from MDC. */
    public static void clearStorageContext() {
      MDC.remove("storageType");
      MDC.remove("storageOperation");
    }

    /** Remove performance context from MDC. */
    public static void clearPerformanceContext() {
      MDC.remove("executionTimeMs");
      MDC.remove("component");
    }

    /** Remove error context from MDC. */
    public static void clearErrorContext() {
      MDC.remove("errorType");
      MDC.remove("errorCode");
      MDC.remove("errorComponent");
    }
  }
}
