package com.company.drools.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@DisplayName("RequestTimeoutConfig")
class RequestTimeoutConfigTest {

  private TimeoutConfig timeoutConfig;
  private RequestTimeoutConfig requestTimeoutConfig;

  @BeforeEach
  void setUp() throws Exception {
    timeoutConfig = new TimeoutConfig();
    setField(timeoutConfig, "httpConnectionTimeoutSeconds", 10);
    setField(timeoutConfig, "httpReadTimeoutSeconds", 30);
    setField(timeoutConfig, "ruleExecutionTimeoutSeconds", 30);
    setField(timeoutConfig, "storageOperationTimeoutSeconds", 60);
    setField(timeoutConfig, "cacheOperationTimeoutSeconds", 5);

    requestTimeoutConfig = new RequestTimeoutConfig();
  }

  @Test
  @DisplayName("requestTimeoutFilter creates filter registration bean")
  void testFilterRegistration() {
    FilterRegistrationBean<RequestTimeoutConfig.RequestTimeoutFilter> registration =
        requestTimeoutConfig.requestTimeoutFilter(timeoutConfig);

    assertThat(registration).isNotNull();
    assertThat(registration.getFilter()).isNotNull();
    assertThat(registration.getOrder()).isEqualTo(1);
  }

  @Test
  @DisplayName("filter sets timeout headers")
  void testFilterSetsTimeoutHeaders() throws Exception {
    RequestTimeoutConfig.RequestTimeoutFilter filter =
        new RequestTimeoutConfig.RequestTimeoutFilter(timeoutConfig);

    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/execute-rule");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, (req, res) -> {});

    assertThat(response.getHeader("X-Request-Timeout")).isEqualTo("30");
    assertThat(response.getHeader("X-Connection-Timeout")).isEqualTo("10");
  }

  @Test
  @DisplayName("filter sets request start time attribute")
  void testFilterSetsStartTime() throws Exception {
    RequestTimeoutConfig.RequestTimeoutFilter filter =
        new RequestTimeoutConfig.RequestTimeoutFilter(timeoutConfig);

    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/health");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(
        request,
        response,
        (req, res) -> {
          Object startTime = ((MockHttpServletRequest) req).getAttribute("request.start.time");
          assertThat(startTime).isNotNull();
          assertThat((long) startTime).isGreaterThan(0);
        });
  }

  @Test
  @DisplayName("filter propagates exceptions")
  void testFilterPropagatesExceptions() {
    RequestTimeoutConfig.RequestTimeoutFilter filter =
        new RequestTimeoutConfig.RequestTimeoutFilter(timeoutConfig);

    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/execute-rule");
    MockHttpServletResponse response = new MockHttpServletResponse();

    assertThatThrownBy(
            () ->
                filter.doFilter(
                    request,
                    response,
                    (req, res) -> {
                      throw new ServletException("test error");
                    }))
        .isInstanceOf(ServletException.class)
        .hasMessage("test error");
  }

  private void setField(Object target, String fieldName, Object value) throws Exception {
    java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    field.set(target, value);
  }
}
