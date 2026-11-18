package com.company.drools.api.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.company.drools.BaseUnitTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("AdminAuthFilter")
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminAuthFilterTest extends BaseUnitTest {

  @Mock private HttpServletRequest request;
  @Mock private HttpServletResponse response;
  @Mock private FilterChain filterChain;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Nested
  @DisplayName("When API key is configured")
  class WithApiKey {

    private AdminAuthFilter createFilter(String apiKey) {
      return new AdminAuthFilter(objectMapper, apiKey, new MockEnvironment());
    }

    @Test
    @DisplayName("allows admin request with valid API key")
    void testAllowsValidApiKey() throws Exception {
      AdminAuthFilter filter = createFilter("secret-key-123");
      when(request.getRequestURI()).thenReturn("/admin/health");
      when(request.getHeader("X-Admin-API-Key")).thenReturn("secret-key-123");

      filter.doFilter(request, response, filterChain);

      verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("rejects admin request with invalid API key")
    void testRejectsInvalidApiKey() throws Exception {
      AdminAuthFilter filter = createFilter("secret-key-123");
      when(request.getRequestURI()).thenReturn("/admin/health");
      when(request.getHeader("X-Admin-API-Key")).thenReturn("wrong-key");
      when(request.getRemoteAddr()).thenReturn("10.0.0.1");

      StringWriter stringWriter = new StringWriter();
      when(response.getWriter()).thenReturn(new PrintWriter(stringWriter));

      filter.doFilter(request, response, filterChain);

      verify(response).setStatus(401);
      verify(filterChain, never()).doFilter(request, response);
      assertThat(stringWriter.toString()).contains("UNAUTHORIZED");
    }

    @Test
    @DisplayName("rejects admin request with missing API key header")
    void testRejectsMissingApiKey() throws Exception {
      AdminAuthFilter filter = createFilter("secret-key-123");
      when(request.getRequestURI()).thenReturn("/admin/rules");
      when(request.getRemoteAddr()).thenReturn("10.0.0.1");

      StringWriter stringWriter = new StringWriter();
      when(response.getWriter()).thenReturn(new PrintWriter(stringWriter));

      filter.doFilter(request, response, filterChain);

      verify(response).setStatus(401);
      verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("allows non-admin endpoints without API key")
    void testAllowsNonAdminEndpoints() throws Exception {
      AdminAuthFilter filter = createFilter("secret-key-123");
      when(request.getRequestURI()).thenReturn("/execute-rule");

      filter.doFilter(request, response, filterChain);

      verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("allows actuator endpoints without API key")
    void testAllowsActuatorEndpoints() throws Exception {
      AdminAuthFilter filter = createFilter("secret-key-123");
      when(request.getRequestURI()).thenReturn("/actuator/health");

      filter.doFilter(request, response, filterChain);

      verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("protects admin refresh-rules endpoint")
    void testProtectsRefreshRules() throws Exception {
      AdminAuthFilter filter = createFilter("my-key");
      when(request.getRequestURI()).thenReturn("/admin/refresh-rules");
      when(request.getRemoteAddr()).thenReturn("10.0.0.1");

      StringWriter stringWriter = new StringWriter();
      when(response.getWriter()).thenReturn(new PrintWriter(stringWriter));

      filter.doFilter(request, response, filterChain);

      verify(response).setStatus(401);
    }

    @Test
    @DisplayName("error response contains proper JSON format")
    void testErrorResponseFormat() throws Exception {
      AdminAuthFilter filter = createFilter("secret");
      when(request.getRequestURI()).thenReturn("/admin/health");
      when(request.getRemoteAddr()).thenReturn("10.0.0.1");

      StringWriter stringWriter = new StringWriter();
      when(response.getWriter()).thenReturn(new PrintWriter(stringWriter));

      filter.doFilter(request, response, filterChain);

      String body = stringWriter.toString();
      @SuppressWarnings("unchecked")
      java.util.Map<String, Object> parsed = objectMapper.readValue(body, java.util.Map.class);

      assertThat(parsed).containsKey("error");
      @SuppressWarnings("unchecked")
      java.util.Map<String, Object> error = (java.util.Map<String, Object>) parsed.get("error");
      assertThat(error)
          .containsEntry("code", "UNAUTHORIZED")
          .containsEntry("message", "Admin API key required")
          .containsKey("timestamp");
    }
  }

  @Nested
  @DisplayName("When API key is not configured (non-deployable profile)")
  class WithoutApiKey {

    @Test
    @DisplayName("allows admin requests when API key is empty")
    void testAllowsAdminWhenKeyEmpty() throws Exception {
      AdminAuthFilter filter = new AdminAuthFilter(objectMapper, "", new MockEnvironment());
      when(request.getRequestURI()).thenReturn("/admin/health");

      filter.doFilter(request, response, filterChain);

      verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("allows admin requests when API key is null")
    void testAllowsAdminWhenKeyNull() throws Exception {
      AdminAuthFilter filter = new AdminAuthFilter(objectMapper, null, new MockEnvironment());
      when(request.getRequestURI()).thenReturn("/admin/rules");

      filter.doFilter(request, response, filterChain);

      verify(filterChain).doFilter(request, response);
    }
  }

  @Nested
  @DisplayName("Fail-closed in deployable profiles (P1)")
  class FailClosed {

    private MockEnvironment envWithProfile(String profile) {
      MockEnvironment env = new MockEnvironment();
      env.setActiveProfiles(profile);
      return env;
    }

    @Test
    @DisplayName("throws when key is blank in the prod profile")
    void testFailsClosedProd() {
      MockEnvironment env = envWithProfile("prod");
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> new AdminAuthFilter(objectMapper, "", env))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("ADMIN_API_KEY");
    }

    @Test
    @DisplayName("throws when key is null in the docker profile")
    void testFailsClosedDocker() {
      MockEnvironment env = envWithProfile("docker");
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> new AdminAuthFilter(objectMapper, null, env))
          .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("does not throw when key is blank in the local profile")
    void testOpenInLocal() {
      // local/dev keep the open-with-WARN behavior — construction must not throw.
      MockEnvironment env = envWithProfile("local");
      org.assertj.core.api.Assertions.assertThatCode(
              () -> new AdminAuthFilter(objectMapper, "", env))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("starts when a key is configured in a deployable profile")
    void testConfiguredKeyProdStarts() {
      MockEnvironment env = envWithProfile("prod");
      org.assertj.core.api.Assertions.assertThatCode(
              () -> new AdminAuthFilter(objectMapper, "a-key", env))
          .doesNotThrowAnyException();
    }
  }
}
