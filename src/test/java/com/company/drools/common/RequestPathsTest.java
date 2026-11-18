package com.company.drools.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;

@DisplayName("RequestPaths")
class RequestPathsTest {

  private static String normalize(String uri) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
    return RequestPaths.normalizedPath(request);
  }

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
    "/admin/health, /admin/health",
    "/execute-rule, /execute-rule",
    "/%61dmin/health, /admin/health",
    "/%65xecute-rule, /execute-rule",
    "/admin;x/health, /admin/health",
    "/admin/health;jsessionid=1, /admin/health",
    "/execute-rule;foo=bar, /execute-rule",
    "//admin//health, /admin/health",
    "/admin/refresh-rules/pricing.discount.simple, /admin/refresh-rules/pricing.discount.simple"
  })
  void testNormalizes(String raw, String expected) {
    assertThat(normalize(raw)).isEqualTo(expected);
  }

  @Test
  @DisplayName("strips the context path")
  void testStripsContextPath() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ctx/admin/health");
    request.setContextPath("/ctx");
    assertThat(RequestPaths.normalizedPath(request)).isEqualTo("/admin/health");
  }

  @Test
  @DisplayName("returns '/' for an empty or missing URI")
  void testEmptyUri() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRequestURI("");
    assertThat(RequestPaths.normalizedPath(request)).isEqualTo("/");
  }

  @Test
  @DisplayName("keeps the raw path when a percent-escape is malformed")
  void testMalformedEscapeKeptRaw() {
    assertThat(normalize("/admin/%zz")).isEqualTo("/admin/%zz");
  }

  @Test
  @DisplayName("trailing ';' content without a following '/' is removed")
  void testTrailingSemicolon() {
    assertThat(RequestPaths.removeSemicolonContent("/a/b;c")).isEqualTo("/a/b");
    assertThat(RequestPaths.removeSemicolonContent("/a;x/b;y")).isEqualTo("/a/b");
    assertThat(RequestPaths.collapseSlashes("///a//b/")).isEqualTo("/a/b/");
  }
}
