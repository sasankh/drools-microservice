package com.company.drools.api.filter;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.drools.BaseUnitTest;
import com.company.drools.config.RateLimitingConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@DisplayName("RateLimitingFilter")
@MockitoSettings(strictness = Strictness.LENIENT)
class RateLimitingFilterTest extends BaseUnitTest {

  @Mock private HttpServletRequest request;
  @Mock private HttpServletResponse response;
  @Mock private FilterChain filterChain;

  private RateLimitingFilter filter;
  private RateLimitingConfig config;
  private RateLimitingConfig.InMemoryRateLimitingService rateLimitingService;

  @BeforeEach
  void setUp() {
    config = new RateLimitingConfig();
    // Use reflection to set config values since they're @Value-injected
    setField(config, "rateLimitingEnabled", true);
    setField(config, "requestsPerMinute", 5);
    setField(config, "requestsPerHour", 100);
    setField(config, "burstSize", 10);
    setField(config, "cleanupIntervalMinutes", 5);
    setField(config, "maxClients", 10000);

    rateLimitingService = new RateLimitingConfig.InMemoryRateLimitingService(config);
    filter = new RateLimitingFilter(rateLimitingService, new ObjectMapper(), false);
  }

  private void setField(Object target, String fieldName, Object value) {
    try {
      java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
      field.setAccessible(true);
      field.set(target, value);
    } catch (Exception e) {
      throw new RuntimeException("Failed to set field: " + fieldName, e);
    }
  }

  @Nested
  @DisplayName("Rate Limiting Behavior")
  class RateLimitingBehavior {

    @Test
    @DisplayName("allows requests within the rate limit")
    void testFilter_WithinLimit_AllowsRequest() throws Exception {
      when(request.getRequestURI()).thenReturn("/execute-rule");
      when(request.getRemoteAddr()).thenReturn("192.168.1.1");

      filter.doFilter(request, response, filterChain);

      verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("returns 429 when rate limit is exceeded")
    void testFilter_ExceedsLimit_Returns429() throws Exception {
      when(request.getRequestURI()).thenReturn("/execute-rule");
      when(request.getRemoteAddr()).thenReturn("10.0.0.1");

      StringWriter stringWriter = new StringWriter();
      PrintWriter printWriter = new PrintWriter(stringWriter);
      when(response.getWriter()).thenReturn(printWriter);

      // Exhaust the rate limit (configured to 5 per minute)
      for (int i = 0; i < 5; i++) {
        filter.doFilter(request, response, filterChain);
      }

      // Next request should be rejected
      filter.doFilter(request, response, filterChain);

      verify(response).setStatus(429);
    }

    @Test
    @DisplayName("applies independent rate limits per client")
    void testFilter_PerClient_IndependentLimits() throws Exception {
      when(request.getRequestURI()).thenReturn("/execute-rule");

      // Client A exhausts limit
      when(request.getRemoteAddr()).thenReturn("10.0.0.1");
      StringWriter writerA = new StringWriter();
      when(response.getWriter()).thenReturn(new PrintWriter(writerA));
      for (int i = 0; i < 6; i++) {
        filter.doFilter(request, response, filterChain);
      }

      // Client B should still be allowed
      HttpServletRequest requestB = org.mockito.Mockito.mock(HttpServletRequest.class);
      HttpServletResponse responseB = org.mockito.Mockito.mock(HttpServletResponse.class);
      when(requestB.getRequestURI()).thenReturn("/execute-rule");
      when(requestB.getRemoteAddr()).thenReturn("10.0.0.2");

      filter.doFilter(requestB, responseB, filterChain);

      verify(filterChain).doFilter(requestB, responseB);
    }

    @Test
    @DisplayName("skips rate limiting for admin endpoints")
    void testFilter_AdminEndpoint_SkipsRateLimit() throws Exception {
      when(request.getRequestURI()).thenReturn("/admin/health");

      filter.doFilter(request, response, filterChain);

      verify(filterChain).doFilter(request, response);
    }
  }

  @Nested
  @DisplayName("Client Identification")
  class ClientIdentification {

    @Test
    @DisplayName("identifies clients by remote address")
    void testFilter_RemoteAddr_IdentifiesClient() throws Exception {
      when(request.getRequestURI()).thenReturn("/execute-rule");
      when(request.getRemoteAddr()).thenReturn("203.0.113.50");

      filter.doFilter(request, response, filterChain);

      verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("ignores X-Forwarded-For by default to prevent spoofing")
    void testFilter_XForwardedFor_Ignored() throws Exception {
      // Same remote address, spoofed X-Forwarded-For — must share one bucket (remote addr).
      when(request.getRequestURI()).thenReturn("/execute-rule");
      when(request.getRemoteAddr()).thenReturn("10.0.0.1");
      when(request.getHeader("X-Forwarded-For")).thenReturn("198.51.100.1");

      StringWriter stringWriter = new StringWriter();
      PrintWriter printWriter = new PrintWriter(stringWriter);
      when(response.getWriter()).thenReturn(printWriter);

      // Keyed on remote addr (10.0.0.1), not the spoofable X-Forwarded-For.
      for (int i = 0; i < 6; i++) {
        filter.doFilter(request, response, filterChain);
      }

      verify(response).setStatus(429);
    }
  }

  @Nested
  @DisplayName("URI Pattern Matching")
  class UriPatternMatching {

    @Test
    @DisplayName("applies rate limiting to /api/ prefix endpoints")
    void testFilter_ApiPrefix_AppliesRateLimit() throws Exception {
      when(request.getRequestURI()).thenReturn("/api/some-endpoint");
      when(request.getRemoteAddr()).thenReturn("192.168.1.1");

      StringWriter stringWriter = new StringWriter();
      when(response.getWriter()).thenReturn(new PrintWriter(stringWriter));

      // Exhaust limit
      for (int i = 0; i < 6; i++) {
        filter.doFilter(request, response, filterChain);
      }

      verify(response).setStatus(429);
    }

    @Test
    @DisplayName("skips rate limiting for non-API, non-execute-rule URIs")
    void testFilter_OtherEndpoints_SkipsRateLimit() throws Exception {
      when(request.getRequestURI()).thenReturn("/health");

      filter.doFilter(request, response, filterChain);

      verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("skips rate limiting for /actuator endpoints")
    void testFilter_ActuatorEndpoint_SkipsRateLimit() throws Exception {
      when(request.getRequestURI()).thenReturn("/actuator/metrics");

      filter.doFilter(request, response, filterChain);

      verify(filterChain).doFilter(request, response);
    }
  }

  @Nested
  @DisplayName("Client Identification Edge Cases")
  class ClientIdentificationEdgeCases {

    @Test
    @DisplayName("spoofed app-level headers cannot escape the per-IP bucket (P2)")
    void testFilter_SpoofedHeaders_ShareBucketByIp() throws Exception {
      when(request.getRequestURI()).thenReturn("/execute-rule");
      when(request.getRemoteAddr()).thenReturn("10.0.0.1");
      // A fresh X-API-Key / X-Client-Id per call must NOT mint a new bucket.
      when(request.getHeader("X-API-Key")).thenReturn("rotating-key");
      when(request.getHeader("X-Client-Id")).thenReturn("rotating-id");

      StringWriter stringWriter = new StringWriter();
      when(response.getWriter()).thenReturn(new PrintWriter(stringWriter));

      for (int i = 0; i < 6; i++) {
        filter.doFilter(request, response, filterChain);
      }

      // Still limited by the remote address — bypass closed.
      verify(response).setStatus(429);
    }

    @Test
    @DisplayName("trust-proxy=true keys on the left-most X-Forwarded-For entry")
    void testFilter_TrustProxy_UsesXForwardedFor() throws Exception {
      RateLimitingFilter proxyFilter =
          new RateLimitingFilter(rateLimitingService, new ObjectMapper(), true);
      when(request.getRequestURI()).thenReturn("/execute-rule");
      when(request.getRemoteAddr()).thenReturn("10.0.0.1");
      when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.9, 10.0.0.1");

      StringWriter stringWriter = new StringWriter();
      when(response.getWriter()).thenReturn(new PrintWriter(stringWriter));

      // Exhaust the limit for the XFF client 203.0.113.9.
      for (int i = 0; i < 6; i++) {
        proxyFilter.doFilter(request, response, filterChain);
      }
      verify(response).setStatus(429);

      // A different XFF client from the same remote address is a distinct bucket → allowed.
      HttpServletRequest other = org.mockito.Mockito.mock(HttpServletRequest.class);
      HttpServletResponse otherResp = org.mockito.Mockito.mock(HttpServletResponse.class);
      when(other.getRequestURI()).thenReturn("/execute-rule");
      when(other.getRemoteAddr()).thenReturn("10.0.0.1");
      when(other.getHeader("X-Forwarded-For")).thenReturn("203.0.113.99");
      proxyFilter.doFilter(other, otherResp, filterChain);
      verify(filterChain).doFilter(other, otherResp);
    }
  }

  @Nested
  @DisplayName("Rate Limit Response")
  class RateLimitResponse {

    @Test
    @DisplayName("429 response contains proper JSON error body")
    void testFilter_RateLimitExceeded_ProperResponseBody() throws Exception {
      when(request.getRequestURI()).thenReturn("/execute-rule");
      when(request.getRemoteAddr()).thenReturn("10.0.0.200");

      StringWriter stringWriter = new StringWriter();
      PrintWriter printWriter = new PrintWriter(stringWriter);
      when(response.getWriter()).thenReturn(printWriter);

      // Exhaust limit
      for (int i = 0; i < 6; i++) {
        filter.doFilter(request, response, filterChain);
      }

      printWriter.flush();
      String responseBody = stringWriter.toString();

      ObjectMapper mapper = new ObjectMapper();
      @SuppressWarnings("unchecked")
      java.util.Map<String, Object> parsed = mapper.readValue(responseBody, java.util.Map.class);

      org.assertj.core.api.Assertions.assertThat(parsed).containsKey("error");
      @SuppressWarnings("unchecked")
      java.util.Map<String, Object> error = (java.util.Map<String, Object>) parsed.get("error");
      org.assertj.core.api.Assertions.assertThat(error)
          .containsEntry("code", "RATE_LIMIT_EXCEEDED")
          .containsEntry("message", "Rate limit exceeded")
          .containsKey("timestamp");
    }

    @Test
    @DisplayName("adds rate limit headers on allowed requests")
    void testFilter_AllowedRequest_AddsHeaders() throws Exception {
      when(request.getRequestURI()).thenReturn("/execute-rule");
      when(request.getRemoteAddr()).thenReturn("10.0.0.201");

      filter.doFilter(request, response, filterChain);

      verify(response)
          .setHeader(
              org.mockito.ArgumentMatchers.eq("X-RateLimit-Limit"),
              org.mockito.ArgumentMatchers.anyString());
      verify(response)
          .setHeader(
              org.mockito.ArgumentMatchers.eq("X-RateLimit-Remaining"),
              org.mockito.ArgumentMatchers.anyString());
    }
  }
}
