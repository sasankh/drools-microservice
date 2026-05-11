package com.company.drools.api.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.drools.api.dto.RuleExecutionResponse;
import com.fasterxml.jackson.core.JsonParseException;
import java.lang.reflect.Method;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@DisplayName("GlobalExceptionHandler")
class GlobalExceptionHandlerTest {

  private GlobalExceptionHandler handler;

  @BeforeEach
  void setUp() {
    handler = new GlobalExceptionHandler();
  }

  @Test
  @DisplayName("handles RuleNotFoundException with 404")
  void testHandleRuleNotFoundException() {
    RuleNotFoundException ex = new RuleNotFoundException("test.rule");

    ResponseEntity<RuleExecutionResponse> response = handler.handleRuleNotFoundException(ex);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody().getError().getCode()).isEqualTo("RULE_NOT_FOUND");
  }

  @Test
  @DisplayName("handles RuleExecutionException with 400")
  void testHandleRuleExecutionException() {
    RuleExecutionException ex = new RuleExecutionException("test.rule", "NPE in rule");

    ResponseEntity<RuleExecutionResponse> response = handler.handleRuleExecutionException(ex);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody().getError().getCode()).isEqualTo("RULE_EXECUTION_ERROR");
  }

  @Test
  @DisplayName("handles TimeoutException with 408")
  void testHandleTimeoutException() {
    TimeoutException ex = new TimeoutException("rule-execution", 30);

    ResponseEntity<RuleExecutionResponse> response = handler.handleTimeoutException(ex);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.REQUEST_TIMEOUT);
    assertThat(response.getBody().getError().getCode()).isEqualTo("TIMEOUT_ERROR");
    assertThat(response.getBody().getError().getMessage()).contains("30 seconds");
  }

  @Test
  @DisplayName("handles CircuitBreakerException with 503")
  void testHandleCircuitBreakerException() {
    CircuitBreakerException ex = new CircuitBreakerException("s3", "OPEN");

    ResponseEntity<RuleExecutionResponse> response = handler.handleCircuitBreakerException(ex);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(response.getBody().getError().getCode()).isEqualTo("SERVICE_UNAVAILABLE");
    assertThat(response.getBody().getError().getMessage()).contains("s3");
  }

  @Test
  @DisplayName("handles malformed JSON with 400 INVALID_INPUT (Finding #2)")
  void testHandleMalformedJson() {
    JsonParseException cause =
        new JsonParseException(null, "Unexpected end-of-input: expected close marker for Object");
    HttpMessageNotReadableException ex =
        new HttpMessageNotReadableException(
            "JSON parse error", cause, new MockHttpInputMessage(new byte[0]));

    ResponseEntity<RuleExecutionResponse> response = handler.handleMalformedJson(ex);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody().getError().getCode()).isEqualTo("INVALID_INPUT");
    assertThat(response.getBody().getError().getMessage())
        .isEqualTo("Request body is not valid JSON");
    // Raw parser cause must NOT be echoed to client.
    assertThat(response.getBody().getError().getDetails())
        .doesNotContain("Unexpected end-of-input");
  }

  @Test
  @DisplayName("handles IllegalArgumentException with 400")
  void testHandleIllegalArgumentException() {
    IllegalArgumentException ex = new IllegalArgumentException("invalid input");

    ResponseEntity<RuleExecutionResponse> response = handler.handleIllegalArgumentException(ex);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody().getError().getCode()).isEqualTo("INVALID_INPUT");
  }

  @Test
  @DisplayName("handles MaxUploadSizeExceededException with 413")
  void testHandleMaxUploadSizeExceededException() {
    MaxUploadSizeExceededException ex = new MaxUploadSizeExceededException(10_000_000);

    ResponseEntity<RuleExecutionResponse> response =
        handler.handleMaxUploadSizeExceededException(ex);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
    assertThat(response.getBody().getError().getCode()).isEqualTo("REQUEST_TOO_LARGE");
  }

  @Test
  @DisplayName("handles NoResourceFoundException with 404")
  void testHandleNoResourceFoundException() {
    NoResourceFoundException ex =
        new NoResourceFoundException(org.springframework.http.HttpMethod.GET, "/nonexistent");

    ResponseEntity<RuleExecutionResponse> response = handler.handleNoResourceFoundException(ex);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody().getError().getCode()).isEqualTo("NOT_FOUND");
  }

  @Test
  @DisplayName("handles generic Exception with 500")
  void testHandleGenericException() {
    Exception ex = new RuntimeException("unexpected error");

    ResponseEntity<RuleExecutionResponse> response = handler.handleGenericException(ex);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(response.getBody().getError().getCode()).isEqualTo("INTERNAL_ERROR");
  }

  @Test
  @DisplayName("handles MethodArgumentNotValidException with 400")
  void testHandleValidationException() throws Exception {
    BeanPropertyBindingResult bindingResult =
        new BeanPropertyBindingResult(new Object(), "request");
    bindingResult.addError(new FieldError("request", "ruleId", "Rule ID cannot be null"));
    bindingResult.addError(new FieldError("request", "data", "Data cannot be null"));

    // Need a real MethodParameter to avoid NPE in getMessage()
    Method method = String.class.getMethod("toString");
    MethodParameter methodParameter = new MethodParameter(method, -1);

    MethodArgumentNotValidException ex =
        new MethodArgumentNotValidException(methodParameter, bindingResult);

    ResponseEntity<RuleExecutionResponse> response = handler.handleValidationException(ex);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody().getError().getCode()).isEqualTo("INVALID_INPUT");
  }
}
