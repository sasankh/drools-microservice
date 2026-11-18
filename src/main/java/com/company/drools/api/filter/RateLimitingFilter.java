package com.company.drools.api.filter;

import com.company.drools.config.RateLimitingConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Filter to apply rate limiting to API requests */
@Component
@Order(1) // Execute before other filters
public class RateLimitingFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(RateLimitingFilter.class);

  private final RateLimitingConfig.InMemoryRateLimitingService rateLimitingService;
  private final ObjectMapper objectMapper;

  public RateLimitingFilter(
      RateLimitingConfig.InMemoryRateLimitingService rateLimitingService,
      ObjectMapper objectMapper) {
    this.rateLimitingService = rateLimitingService;
    this.objectMapper = objectMapper;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    // Only apply rate limiting to main API endpoints, not admin endpoints
    if (shouldApplyRateLimit(request)) {
      String clientId = getClientIdentifier(request);

      // Check if request is allowed
      if (!rateLimitingService.isAllowed(clientId)) {
        log.warn("Rate limit exceeded for client: {}", clientId);
        writeRateLimitExceededResponse(response, clientId);
        return;
      }

      // Add rate limit headers to response
      addRateLimitHeaders(response, clientId);
    }

    // Continue with the filter chain
    filterChain.doFilter(request, response);
  }

  private boolean shouldApplyRateLimit(HttpServletRequest request) {
    String uri = request.getRequestURI();

    // Apply rate limiting to main API endpoints
    return uri.startsWith("/execute-rule")
        || (uri.startsWith("/api/") && !uri.startsWith("/admin/"));
  }

  private String getClientIdentifier(HttpServletRequest request) {
    // Try to identify client by various methods
    String clientId;

    // 1. Check for API key header
    clientId = request.getHeader("X-API-Key");
    if (clientId != null && !clientId.isEmpty()) {
      return "api-key:" + clientId;
    }

    // 2. Check for Authorization header (for authenticated clients)
    String authHeader = request.getHeader("Authorization");
    if (authHeader != null && authHeader.startsWith("Bearer ")) {
      // Use a hash of the token to avoid logging sensitive data
      return "bearer:" + Integer.toHexString(authHeader.hashCode());
    }

    // 3. Check for custom client ID header
    clientId = request.getHeader("X-Client-Id");
    if (clientId != null && !clientId.isEmpty()) {
      return "client-id:" + clientId;
    }

    // 4. Fall back to remote address (don't trust X-Forwarded-For — it's spoofable)
    return "ip:" + request.getRemoteAddr();
  }

  private void addRateLimitHeaders(HttpServletResponse response, String clientId) {
    try {
      RateLimitingConfig.RateLimitInfo info = rateLimitingService.getRateLimitInfo(clientId);

      response.setHeader("X-RateLimit-Limit", String.valueOf(info.getLimit()));
      response.setHeader("X-RateLimit-Remaining", String.valueOf(info.getRemaining()));
      response.setHeader(
          "X-RateLimit-Reset",
          String.valueOf(System.currentTimeMillis() / 1000 + info.getResetTimeSeconds()));
      response.setHeader("X-RateLimit-Reset-After", String.valueOf(info.getResetTimeSeconds()));

    } catch (Exception e) {
      log.debug("Failed to add rate limit headers", e);
      // Don't fail the request if we can't add headers
    }
  }

  private void writeRateLimitExceededResponse(HttpServletResponse response, String clientId)
      throws IOException {
    response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);

    // Add rate limit headers even for rejected requests
    addRateLimitHeaders(response, clientId);

    Map<String, Object> errorResponse = new HashMap<>();
    errorResponse.put("rule_id", null);
    errorResponse.put("result", null);

    Map<String, Object> error = new HashMap<>();
    error.put("code", "RATE_LIMIT_EXCEEDED");
    error.put("message", "Rate limit exceeded");
    error.put("details", "Too many requests. Please try again later.");
    error.put("timestamp", java.time.Instant.now().toString());

    errorResponse.put("error", error);

    String jsonResponse = objectMapper.writeValueAsString(errorResponse);
    response.getWriter().write(jsonResponse);
    response.getWriter().flush();
  }
}
