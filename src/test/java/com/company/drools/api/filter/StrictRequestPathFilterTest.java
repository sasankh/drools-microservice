package com.company.drools.api.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@DisplayName("StrictRequestPathFilter")
class StrictRequestPathFilterTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final StrictRequestPathFilter filter = new StrictRequestPathFilter(objectMapper);

  private MockHttpServletResponse run(String uri, MockFilterChain chain) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, chain);
    return response;
  }

  @ParameterizedTest(name = "rejects {0}")
  @ValueSource(
      strings = {
        "/admin;x/health",
        "/admin/health;jsessionid=1",
        "/%61dmin/health",
        "/admin/%2e%2e/admin/health",
        "/execute-rule;x",
        "/%65xecute-rule",
        "//admin/health",
        "/admin//health",
        "/admin/./health",
        "/admin/../admin/health",
        "/admin/..",
        "/admin\\health"
      })
  void testRejectsAmbiguousPaths(String uri) throws Exception {
    MockFilterChain chain = new MockFilterChain();

    MockHttpServletResponse response = run(uri, chain);

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(chain.getRequest()).as("chain must not be called").isNull();
    assertThat(response.getContentType()).startsWith("application/json");
    @SuppressWarnings("unchecked")
    Map<String, Object> body = objectMapper.readValue(response.getContentAsString(), Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> error = (Map<String, Object>) body.get("error");
    assertThat(error)
        .containsEntry("code", "INVALID_INPUT")
        .containsEntry("message", "Malformed request path")
        .containsKey("timestamp");
  }

  @ParameterizedTest(name = "allows {0}")
  @ValueSource(
      strings = {
        "/",
        "/execute-rule",
        "/admin/health",
        "/admin/memory/info",
        "/admin/refresh-rules/pricing.discount.simple",
        "/admin/refresh-rules/a.b-c_d",
        "/actuator/health"
      })
  void testAllowsNormalPaths(String uri) throws Exception {
    MockFilterChain chain = new MockFilterChain();

    MockHttpServletResponse response = run(uri, chain);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(chain.getRequest()).as("chain must be called").isNotNull();
  }

  @Test
  @DisplayName("treats null/empty paths as not ambiguous")
  void testNullAndEmpty() {
    assertThat(StrictRequestPathFilter.isAmbiguous(null)).isFalse();
    assertThat(StrictRequestPathFilter.isAmbiguous("")).isFalse();
  }

  @Test
  @DisplayName("dots inside a segment (rule IDs) are allowed")
  void testDotsInsideSegmentAllowed() {
    assertThat(StrictRequestPathFilter.isAmbiguous("/admin/refresh-rules/x.y.z")).isFalse();
    assertThat(StrictRequestPathFilter.isAmbiguous("/admin/refresh-rules/..x")).isFalse();
  }
}
