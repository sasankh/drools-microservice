package com.company.drools.api.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.drools.BaseUnitTest;
import com.company.drools.config.ValidationConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@DisplayName("RequestSizeValidationFilter")
@MockitoSettings(strictness = Strictness.LENIENT)
class RequestSizeValidationFilterTest extends BaseUnitTest {

  @Mock private HttpServletRequest request;
  @Mock private HttpServletResponse response;
  @Mock private FilterChain filterChain;

  private RequestSizeValidationFilter filter;
  private ValidationConfig validationConfig;
  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    validationConfig = new ValidationConfig();
    setField(validationConfig, "requestMaxSizeBytes", 1048576L); // 1MB
    objectMapper = new ObjectMapper();
    filter = new RequestSizeValidationFilter(validationConfig, objectMapper);
  }

  private void setField(Object target, String fieldName, Object value) {
    try {
      java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
      field.setAccessible(true);
      field.set(target, value);
    } catch (Exception e) {
      throw new RuntimeException("Failed to set field: " + fieldName, e);
    }
  }

  @Nested
  @DisplayName("Request Size Validation")
  class RequestSizeValidation {

    @Test
    @DisplayName("allows POST to /execute-rule within size limit")
    void testAllowsRequestWithinLimit() throws Exception {
      when(request.getMethod()).thenReturn("POST");
      when(request.getRequestURI()).thenReturn("/execute-rule");
      when(request.getContentLengthLong()).thenReturn(1024L);

      filter.doFilterInternal(request, response, filterChain);

      verify(filterChain).doFilter(request, response);
      verify(response, never()).setStatus(413);
    }

    @Test
    @DisplayName("rejects POST to /execute-rule exceeding size limit")
    void testRejectsOversizedRequest() throws Exception {
      when(request.getMethod()).thenReturn("POST");
      when(request.getRequestURI()).thenReturn("/execute-rule");
      when(request.getContentLengthLong()).thenReturn(2000000L);

      StringWriter stringWriter = new StringWriter();
      when(response.getWriter()).thenReturn(new PrintWriter(stringWriter));

      filter.doFilterInternal(request, response, filterChain);

      verify(response).setStatus(413);
      verify(response).setContentType("application/json");
      verify(filterChain, never()).doFilter(request, response);

      String responseBody = stringWriter.toString();
      assertThat(responseBody).contains("REQUEST_TOO_LARGE");
      assertThat(responseBody).contains("Request size exceeds maximum limit");
      assertThat(responseBody).contains("2000000");
    }

    @Test
    @DisplayName("allows request at exactly the size limit")
    void testAllowsRequestAtExactLimit() throws Exception {
      when(request.getMethod()).thenReturn("POST");
      when(request.getRequestURI()).thenReturn("/execute-rule");
      when(request.getContentLengthLong()).thenReturn(1048576L); // exactly 1MB

      filter.doFilterInternal(request, response, filterChain);

      verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("rejects request one byte over the limit")
    void testRejectsRequestOneByteOver() throws Exception {
      when(request.getMethod()).thenReturn("POST");
      when(request.getRequestURI()).thenReturn("/execute-rule");
      when(request.getContentLengthLong()).thenReturn(1048577L); // 1 byte over

      StringWriter stringWriter = new StringWriter();
      when(response.getWriter()).thenReturn(new PrintWriter(stringWriter));

      filter.doFilterInternal(request, response, filterChain);

      verify(response).setStatus(413);
      verify(filterChain, never()).doFilter(request, response);
    }
  }

  @Nested
  @DisplayName("Request Method Filtering")
  class RequestMethodFiltering {

    @Test
    @DisplayName("skips validation for GET requests")
    void testSkipsGetRequests() throws Exception {
      when(request.getMethod()).thenReturn("GET");
      when(request.getRequestURI()).thenReturn("/execute-rule");

      filter.doFilterInternal(request, response, filterChain);

      verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("skips validation for PUT requests")
    void testSkipsPutRequests() throws Exception {
      when(request.getMethod()).thenReturn("PUT");
      when(request.getRequestURI()).thenReturn("/execute-rule");

      filter.doFilterInternal(request, response, filterChain);

      verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("skips validation for DELETE requests")
    void testSkipsDeleteRequests() throws Exception {
      when(request.getMethod()).thenReturn("DELETE");
      when(request.getRequestURI()).thenReturn("/execute-rule");

      filter.doFilterInternal(request, response, filterChain);

      verify(filterChain).doFilter(request, response);
    }
  }

  @Nested
  @DisplayName("URI Filtering")
  class UriFiltering {

    @Test
    @DisplayName("skips validation for POST to non-execute-rule endpoints")
    void testSkipsNonExecuteRuleEndpoints() throws Exception {
      when(request.getMethod()).thenReturn("POST");
      when(request.getRequestURI()).thenReturn("/admin/refresh-rules");
      when(request.getContentLengthLong()).thenReturn(2000000L);

      filter.doFilterInternal(request, response, filterChain);

      verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("validates POST to paths containing /execute-rule")
    void testValidatesPathContainingExecuteRule() throws Exception {
      when(request.getMethod()).thenReturn("POST");
      when(request.getRequestURI()).thenReturn("/api/execute-rule");
      when(request.getContentLengthLong()).thenReturn(2000000L);

      StringWriter stringWriter = new StringWriter();
      when(response.getWriter()).thenReturn(new PrintWriter(stringWriter));

      filter.doFilterInternal(request, response, filterChain);

      verify(response).setStatus(413);
    }
  }

  @Nested
  @DisplayName("Missing Content-Length")
  class MissingContentLength {

    @Test
    @DisplayName("allows request when Content-Length is missing (-1)")
    void testAllowsMissingContentLength() throws Exception {
      when(request.getMethod()).thenReturn("POST");
      when(request.getRequestURI()).thenReturn("/execute-rule");
      when(request.getContentLengthLong()).thenReturn(-1L);

      filter.doFilterInternal(request, response, filterChain);

      verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("allows request with zero Content-Length")
    void testAllowsZeroContentLength() throws Exception {
      when(request.getMethod()).thenReturn("POST");
      when(request.getRequestURI()).thenReturn("/execute-rule");
      when(request.getContentLengthLong()).thenReturn(0L);

      filter.doFilterInternal(request, response, filterChain);

      verify(filterChain).doFilter(request, response);
    }
  }

  @Nested
  @DisplayName("Error Response Format")
  class ErrorResponseFormat {

    @Test
    @DisplayName("error response contains all expected fields")
    void testErrorResponseContainsAllFields() throws Exception {
      when(request.getMethod()).thenReturn("POST");
      when(request.getRequestURI()).thenReturn("/execute-rule");
      when(request.getContentLengthLong()).thenReturn(5000000L);

      StringWriter stringWriter = new StringWriter();
      when(response.getWriter()).thenReturn(new PrintWriter(stringWriter));

      filter.doFilterInternal(request, response, filterChain);

      String responseBody = stringWriter.toString();
      @SuppressWarnings("unchecked")
      java.util.Map<String, Object> parsed = objectMapper.readValue(responseBody, java.util.Map.class);

      assertThat(parsed).containsKey("rule_id");
      assertThat(parsed).containsKey("result");
      assertThat(parsed).containsKey("error");
      assertThat(parsed.get("rule_id")).isNull();
      assertThat(parsed.get("result")).isNull();

      @SuppressWarnings("unchecked")
      java.util.Map<String, Object> error = (java.util.Map<String, Object>) parsed.get("error");
      assertThat(error.get("code")).isEqualTo("REQUEST_TOO_LARGE");
      assertThat(error.get("message")).isEqualTo("Request size exceeds maximum limit");
      assertThat((String) error.get("details")).contains("5000000");
      assertThat(error).containsKey("timestamp");
    }
  }
}
