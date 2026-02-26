package com.company.drools.api.filter;

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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Filter to authenticate requests to admin endpoints using an API key. */
@Component
@Order(0) // Execute before rate limiting filter
public class AdminAuthFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(AdminAuthFilter.class);
  private static final String API_KEY_HEADER = "X-Admin-API-Key";

  private final ObjectMapper objectMapper;
  private final String adminApiKey;

  public AdminAuthFilter(
      ObjectMapper objectMapper, @Value("${drools.admin.api-key:}") String adminApiKey) {
    this.objectMapper = objectMapper;
    this.adminApiKey = adminApiKey;

    if (adminApiKey == null || adminApiKey.isBlank()) {
      log.warn(
          "Admin API key is not configured — admin endpoints are unprotected. "
              + "Set ADMIN_API_KEY environment variable for production.");
    } else {
      log.info("Admin endpoint authentication enabled");
    }
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    if (shouldAuthenticate(request)) {
      String providedKey = request.getHeader(API_KEY_HEADER);

      if (providedKey == null || !providedKey.equals(adminApiKey)) {
        log.warn("Unauthorized admin access attempt from {}", request.getRemoteAddr());
        writeUnauthorizedResponse(response);
        return;
      }
    }

    filterChain.doFilter(request, response);
  }

  private boolean shouldAuthenticate(HttpServletRequest request) {
    // Only enforce when an API key is configured
    if (adminApiKey == null || adminApiKey.isBlank()) {
      return false;
    }

    String uri = request.getRequestURI();
    return uri.startsWith("/admin/");
  }

  private void writeUnauthorizedResponse(HttpServletResponse response) throws IOException {
    response.setStatus(HttpStatus.UNAUTHORIZED.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);

    Map<String, Object> errorResponse = new HashMap<>();
    errorResponse.put("rule_id", null);
    errorResponse.put("result", null);

    Map<String, Object> error = new HashMap<>();
    error.put("code", "UNAUTHORIZED");
    error.put("message", "Admin API key required");
    error.put("details", "Provide a valid API key via the " + API_KEY_HEADER + " header");
    error.put("timestamp", java.time.Instant.now().toString());

    errorResponse.put("error", error);

    String jsonResponse = objectMapper.writeValueAsString(errorResponse);
    response.getWriter().write(jsonResponse);
    response.getWriter().flush();
  }
}
