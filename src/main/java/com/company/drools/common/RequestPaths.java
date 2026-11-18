package com.company.drools.common;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import org.springframework.web.util.UriUtils;

/**
 * Request-path normalization shared by the servlet filters.
 *
 * <p>{@link HttpServletRequest#getRequestURI()} is the <em>raw</em> request line path: it still
 * contains {@code ;}-path parameters and percent-encoding. Spring MVC, however, routes on the
 * decoded path with {@code ;} content removed — so {@code /admin;x/health} and {@code
 * /%61dmin/health} both reach {@code /admin/health}. A filter that matches on the raw URI can
 * therefore be bypassed (live-confirmed 2026-11-17, see ADR-024). Filters must match on {@link
 * #normalizedPath(HttpServletRequest)}, which mirrors Spring's view: context path stripped, {@code
 * ;} content removed, percent-decoded, repeated {@code /} collapsed.
 */
public final class RequestPaths {

  private RequestPaths() {}

  /** The request path as Spring MVC routes it. Never null; at least {@code "/"}. */
  public static String normalizedPath(HttpServletRequest request) {
    String uri = request.getRequestURI();
    if (uri == null || uri.isEmpty()) {
      return "/";
    }

    String contextPath = request.getContextPath();
    if (contextPath != null
        && !contextPath.isEmpty()
        && !"/".equals(contextPath)
        && uri.startsWith(contextPath)) {
      uri = uri.substring(contextPath.length());
    }

    String path = removeSemicolonContent(uri);
    try {
      path = UriUtils.decode(path, StandardCharsets.UTF_8);
    } catch (IllegalArgumentException malformedEscape) {
      // Keep the undecoded path; StrictRequestPathFilter rejects any '%' anyway.
    }
    path = collapseSlashes(path);
    return path.isEmpty() ? "/" : path;
  }

  /** Removes {@code ;...} content up to the next {@code /}, like Spring's UrlPathHelper. */
  static String removeSemicolonContent(String uri) {
    int semi = uri.indexOf(';');
    if (semi < 0) {
      return uri;
    }
    StringBuilder sb = new StringBuilder(uri.length());
    int i = 0;
    while (i < uri.length()) {
      char c = uri.charAt(i);
      if (c == ';') {
        int next = uri.indexOf('/', i);
        if (next < 0) {
          break;
        }
        i = next;
      } else {
        sb.append(c);
        i++;
      }
    }
    return sb.toString();
  }

  static String collapseSlashes(String path) {
    if (!path.contains("//")) {
      return path;
    }
    StringBuilder sb = new StringBuilder(path.length());
    char prev = 0;
    for (int i = 0; i < path.length(); i++) {
      char c = path.charAt(i);
      if (c == '/' && prev == '/') {
        continue;
      }
      sb.append(c);
      prev = c;
    }
    return sb.toString();
  }
}
