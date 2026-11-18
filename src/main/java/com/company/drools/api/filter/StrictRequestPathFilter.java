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
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects request paths that are ambiguous between the raw URI and the path Spring MVC routes on
 * (ADR-024). The raw path {@code /admin;x/health} or {@code /%61dmin/health} routes to {@code
 * /admin/health}, which let callers skip URI-prefix checks such as admin authentication.
 *
 * <p>This service has no legitimate need for encoded or parameterised paths — rule IDs are {@code
 * [A-Za-z0-9._-]} and query strings are not part of {@link HttpServletRequest#getRequestURI()} — so
 * any raw path containing {@code ;}, {@code %}, {@code \}, an empty segment ({@code //}) or a
 * {@code .}/{@code ..} segment is rejected with 400 {@code INVALID_INPUT}. Runs right after {@link
 * SecurityHeadersFilter} so the rejection still carries the security headers. Filters additionally
 * match on {@link com.company.drools.common.RequestPaths#normalizedPath} as defense in depth.
 */
@Component
@Order(-1)
public class StrictRequestPathFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(StrictRequestPathFilter.class);

  private final ObjectMapper objectMapper;

  public StrictRequestPathFilter(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    if (isAmbiguous(request.getRequestURI())) {
      // Don't log the raw path itself (attacker-controlled); the source address is enough.
      log.warn("Rejected malformed request path from {}", request.getRemoteAddr());
      writeBadRequest(response);
      return;
    }

    filterChain.doFilter(request, response);
  }

  /** True if the raw request path could be interpreted differently by the router. */
  static boolean isAmbiguous(String rawPath) {
    if (rawPath == null || rawPath.isEmpty()) {
      return false;
    }
    if (rawPath.indexOf(';') >= 0
        || rawPath.indexOf('%') >= 0
        || rawPath.indexOf('\\') >= 0
        || rawPath.contains("//")) {
      return true;
    }
    for (String segment : rawPath.split("/", -1)) {
      if (".".equals(segment) || "..".equals(segment)) {
        return true;
      }
    }
    return false;
  }

  private void writeBadRequest(HttpServletResponse response) throws IOException {
    response.setStatus(HttpStatus.BAD_REQUEST.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);

    Map<String, Object> errorResponse = new HashMap<>();
    errorResponse.put("rule_id", null);
    errorResponse.put("result", null);

    Map<String, Object> error = new HashMap<>();
    error.put("code", "INVALID_INPUT");
    error.put("message", "Malformed request path");
    error.put(
        "details",
        "Request paths must not contain ';', '%', '\\', empty segments or '.'/'..' segments");
    error.put("timestamp", java.time.Instant.now().toString());

    errorResponse.put("error", error);

    response.getWriter().write(objectMapper.writeValueAsString(errorResponse));
    response.getWriter().flush();
  }
}
