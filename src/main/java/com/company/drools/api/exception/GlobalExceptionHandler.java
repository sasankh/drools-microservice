package com.company.drools.api.exception;

import com.company.drools.api.dto.RuleExecutionResponse;
import com.company.drools.common.LogSanitizer;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
@SuppressWarnings("java:S2629") // LogSanitizer.sanitizeMessage() calls are security-motivated
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  private static final String ERROR_CODE_INVALID_INPUT = "INVALID_INPUT";

  @ExceptionHandler(RuleNotFoundException.class)
  public ResponseEntity<RuleExecutionResponse> handleRuleNotFoundException(
      RuleNotFoundException ex) {
    log.warn("Rule not found: {}", LogSanitizer.sanitizeMessage(ex.getRuleId()));

    RuleExecutionResponse response =
        RuleExecutionResponse.failure(ex.getRuleId(), "RULE_NOT_FOUND", ex.getMessage());

    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
  }

  @ExceptionHandler(RuleExecutionException.class)
  public ResponseEntity<RuleExecutionResponse> handleRuleExecutionException(
      RuleExecutionException ex) {
    log.error(
        "Rule execution failed for rule: {}", LogSanitizer.sanitizeMessage(ex.getRuleId()), ex);

    RuleExecutionResponse response =
        RuleExecutionResponse.failure(
            ex.getRuleId(), "RULE_EXECUTION_ERROR", "Rule execution failed");

    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<RuleExecutionResponse> handleValidationException(
      MethodArgumentNotValidException ex) {
    log.warn("Validation failed: {}", LogSanitizer.sanitizeMessage(ex.getMessage()));

    String errors =
        ex.getBindingResult().getFieldErrors().stream()
            .map(FieldError::getDefaultMessage)
            .collect(Collectors.joining(", "));

    RuleExecutionResponse response =
        RuleExecutionResponse.failure(
            null, ERROR_CODE_INVALID_INPUT, "Request validation failed", errors);

    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
  }

  @ExceptionHandler(TimeoutException.class)
  public ResponseEntity<RuleExecutionResponse> handleTimeoutException(TimeoutException ex) {
    log.error("Operation timed out: {} after {}s", ex.getOperation(), ex.getTimeoutSeconds(), ex);

    RuleExecutionResponse response =
        RuleExecutionResponse.failure(
            null,
            "TIMEOUT_ERROR",
            String.format(
                "Operation '%s' timed out after %d seconds",
                ex.getOperation(), ex.getTimeoutSeconds()));

    return ResponseEntity.status(HttpStatus.REQUEST_TIMEOUT).body(response);
  }

  @ExceptionHandler(CircuitBreakerException.class)
  public ResponseEntity<RuleExecutionResponse> handleCircuitBreakerException(
      CircuitBreakerException ex) {
    log.error(
        "Circuit breaker {} is {}: {}",
        ex.getCircuitBreakerName(),
        ex.getState(),
        ex.getMessage(),
        ex);

    RuleExecutionResponse response =
        RuleExecutionResponse.failure(
            null,
            "SERVICE_UNAVAILABLE",
            String.format(
                "External service '%s' is temporarily unavailable (%s). Please try again later.",
                ex.getCircuitBreakerName(), ex.getState().toLowerCase()));

    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<RuleExecutionResponse> handleIllegalArgumentException(
      IllegalArgumentException ex) {
    log.warn("Invalid argument: {}", ex.getMessage());

    RuleExecutionResponse response =
        RuleExecutionResponse.failure(null, ERROR_CODE_INVALID_INPUT, "Invalid request parameter");

    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
  }

  /**
   * Map malformed-JSON request bodies to 400 INVALID_INPUT instead of letting them fall through to
   * the generic 500 catch-all. {@link HttpMessageNotReadableException} wraps Jackson parse failures
   * (JsonParseException, JsonMappingException, MismatchedInputException, etc.), so a single handler
   * covers all read-side JSON errors. The raw parser message is logged but never echoed to the
   * client — it can leak fragments of the input.
   */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<RuleExecutionResponse> handleMalformedJson(
      HttpMessageNotReadableException ex) {
    String causeMessage = ex.getMostSpecificCause().getMessage();
    log.warn("Malformed JSON in request body: {}", LogSanitizer.sanitizeMessage(causeMessage));

    RuleExecutionResponse response =
        RuleExecutionResponse.failure(
            null,
            ERROR_CODE_INVALID_INPUT,
            "Request body is not valid JSON",
            "Verify the request body is well-formed JSON matching the documented schema");

    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<RuleExecutionResponse> handleMaxUploadSizeExceededException(
      MaxUploadSizeExceededException ex) {
    log.warn(
        "Request size exceeded maximum allowed size: {}",
        LogSanitizer.sanitizeMessage(ex.getMessage()));

    RuleExecutionResponse response =
        RuleExecutionResponse.failure(
            null,
            "REQUEST_TOO_LARGE",
            "Request size exceeds maximum allowed limit",
            "Please reduce the size of your request payload");

    return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(response);
  }

  /**
   * Handle NoResourceFoundException - Spring Boot 3.x throws this when a path doesn't match any
   * handler. This is normal behavior when Spring Boot tries multiple handlers (e.g., actuator's
   * CompositeHandlerAdapter), so we suppress the error logging to avoid log noise.
   */
  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<RuleExecutionResponse> handleNoResourceFoundException(
      NoResourceFoundException ex) {
    // Log at debug level only - this is normal Spring Boot routing behavior
    log.debug("No handler found for path: {}", ex.getResourcePath());

    RuleExecutionResponse response =
        RuleExecutionResponse.failure(null, "NOT_FOUND", "The requested resource was not found");

    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<RuleExecutionResponse> handleGenericException(Exception ex) {
    log.error("Unexpected error occurred", ex);

    RuleExecutionResponse response =
        RuleExecutionResponse.failure(
            null,
            "INTERNAL_ERROR",
            "An unexpected error occurred",
            "Please contact support if this persists");

    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
  }
}
