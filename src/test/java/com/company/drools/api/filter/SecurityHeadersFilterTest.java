package com.company.drools.api.filter;

import static org.mockito.Mockito.*;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("SecurityHeadersFilter")
@ExtendWith(MockitoExtension.class)
class SecurityHeadersFilterTest {

  @Mock private HttpServletRequest request;
  @Mock private HttpServletResponse response;
  @Mock private FilterChain filterChain;

  private final SecurityHeadersFilter filter = new SecurityHeadersFilter();

  @Test
  @DisplayName("sets all security headers on every response")
  void testSetsAllSecurityHeaders() throws Exception {
    filter.doFilter(request, response, filterChain);

    verify(response).setHeader("X-Content-Type-Options", "nosniff");
    verify(response).setHeader("X-Frame-Options", "DENY");
    verify(response).setHeader("X-XSS-Protection", "0");
    verify(response).setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
    verify(response).setHeader("Cache-Control", "no-store");
    verify(response)
        .setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
    verify(response).setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
    verify(filterChain).doFilter(request, response);
  }
}
