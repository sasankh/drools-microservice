package com.company.drools.api.filter;

import com.company.drools.config.ValidationConfig;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Filter to validate request size limits before processing */
@Component
public class RequestSizeValidationFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(RequestSizeValidationFilter.class);

  private final ValidationConfig validationConfig;
  private final ObjectMapper objectMapper;

  public RequestSizeValidationFilter(ValidationConfig validationConfig, ObjectMapper objectMapper) {
    this.validationConfig = validationConfig;
    this.objectMapper = objectMapper;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    // Only validate POST requests to execute-rule endpoint
    if ("POST".equalsIgnoreCase(request.getMethod())
        && request.getRequestURI().contains("/execute-rule")) {

      // Check Content-Length header
      long contentLength = request.getContentLengthLong();
      if (contentLength > validationConfig.getRequestMaxSizeBytes()) {
        log.warn(
            "Request size {} exceeds maximum allowed size of {} bytes",
            contentLength,
            validationConfig.getRequestMaxSizeBytes());

        writeErrorResponse(
            response,
            "Request size exceeds maximum limit",
            "Request size: "
                + contentLength
                + " bytes, Maximum allowed: "
                + validationConfig.getRequestMaxSizeBytes()
                + " bytes");
        return;
      }

      // Also check if Content-Length is missing but the request might be large
      if (contentLength == -1) {
        log.debug("Content-Length header is missing for request to {}", request.getRequestURI());
      }
    }

    // Continue with the filter chain
    filterChain.doFilter(request, response);
  }

  private void writeErrorResponse(HttpServletResponse response, String message, String details)
      throws IOException {
    response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);

    Map<String, Object> errorResponse = new HashMap<>();
    errorResponse.put("rule_id", null);
    errorResponse.put("result", null);

    Map<String, Object> error = new HashMap<>();
    error.put("code", "REQUEST_TOO_LARGE");
    error.put("message", message);
    error.put("details", details);
    error.put("timestamp", java.time.Instant.now().toString());

    errorResponse.put("error", error);

    String jsonResponse = objectMapper.writeValueAsString(errorResponse);
    response.getWriter().write(jsonResponse);
    response.getWriter().flush();
  }
}
