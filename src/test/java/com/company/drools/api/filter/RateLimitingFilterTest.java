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

    rateLimitingService = new RateLimitingConfig.InMemoryRateLimitingService(config);
    filter = new RateLimitingFilter(rateLimitingService, new ObjectMapper());
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
    @DisplayName("identifies client by X-API-Key header")
    void testFilter_APIKeyHeader_IdentifiesClient() throws Exception {
      when(request.getRequestURI()).thenReturn("/execute-rule");
      when(request.getHeader("X-API-Key")).thenReturn("test-api-key");

      StringWriter stringWriter = new StringWriter();
      PrintWriter printWriter = new PrintWriter(stringWriter);
      when(response.getWriter()).thenReturn(printWriter);

      // Exhaust limit for API key client
      for (int i = 0; i < 6; i++) {
        filter.doFilter(request, response, filterChain);
      }

      // Same IP but no API key should still be allowed (different client)
      HttpServletRequest ipRequest = org.mockito.Mockito.mock(HttpServletRequest.class);
      HttpServletResponse ipResponse = org.mockito.Mockito.mock(HttpServletResponse.class);
      when(ipRequest.getRequestURI()).thenReturn("/execute-rule");
      when(ipRequest.getRemoteAddr()).thenReturn("192.168.1.1");

      filter.doFilter(ipRequest, ipResponse, filterChain);

      verify(filterChain).doFilter(ipRequest, ipResponse);
    }

    @Test
    @DisplayName("identifies client by Bearer token")
    void testFilter_BearerToken_IdentifiesClient() throws Exception {
      when(request.getRequestURI()).thenReturn("/execute-rule");
      when(request.getHeader("Authorization")).thenReturn("Bearer eyJhbGciOiJIUzI1NiJ9.test");

      StringWriter stringWriter = new StringWriter();
      PrintWriter printWriter = new PrintWriter(stringWriter);
      when(response.getWriter()).thenReturn(printWriter);

      // Exhaust limit for bearer token client
      for (int i = 0; i < 6; i++) {
        filter.doFilter(request, response, filterChain);
      }

      // Different bearer token should be allowed (different client)
      HttpServletRequest otherRequest = org.mockito.Mockito.mock(HttpServletRequest.class);
      HttpServletResponse otherResponse = org.mockito.Mockito.mock(HttpServletResponse.class);
      when(otherRequest.getRequestURI()).thenReturn("/execute-rule");
      when(otherRequest.getHeader("Authorization")).thenReturn("Bearer differentToken123");

      filter.doFilter(otherRequest, otherResponse, filterChain);

      verify(filterChain).doFilter(otherRequest, otherResponse);
    }

    @Test
    @DisplayName("falls back to IP address when no auth headers present")
    void testFilter_IPAddress_FallbackIdentifier() throws Exception {
      when(request.getRequestURI()).thenReturn("/execute-rule");
      when(request.getRemoteAddr()).thenReturn("203.0.113.50");

      // No auth headers set, should fall back to IP
      filter.doFilter(request, response, filterChain);

      verify(filterChain).doFilter(request, response);

      // Verify X-Forwarded-For takes precedence over remote addr
      HttpServletRequest forwardedRequest = org.mockito.Mockito.mock(HttpServletRequest.class);
      HttpServletResponse forwardedResponse = org.mockito.Mockito.mock(HttpServletResponse.class);
      when(forwardedRequest.getRequestURI()).thenReturn("/execute-rule");
      when(forwardedRequest.getHeader("X-Forwarded-For")).thenReturn("198.51.100.1, 10.0.0.1");
      when(forwardedRequest.getRemoteAddr()).thenReturn("203.0.113.50");

      StringWriter stringWriter = new StringWriter();
      PrintWriter printWriter = new PrintWriter(stringWriter);
      when(forwardedResponse.getWriter()).thenReturn(printWriter);

      // Exhaust limit for forwarded IP
      for (int i = 0; i < 6; i++) {
        filter.doFilter(forwardedRequest, forwardedResponse, filterChain);
      }

      // Original IP (203.0.113.50) should still have requests left since the
      // forwarded request used 198.51.100.1 as the client identifier
      HttpServletRequest directRequest = org.mockito.Mockito.mock(HttpServletRequest.class);
      HttpServletResponse directResponse = org.mockito.Mockito.mock(HttpServletResponse.class);
      when(directRequest.getRequestURI()).thenReturn("/execute-rule");
      when(directRequest.getRemoteAddr()).thenReturn("203.0.113.50");

      filter.doFilter(directRequest, directResponse, filterChain);

      // directRequest should still pass because it had only 1 request used earlier
      verify(filterChain).doFilter(directRequest, directResponse);
    }
  }
}
