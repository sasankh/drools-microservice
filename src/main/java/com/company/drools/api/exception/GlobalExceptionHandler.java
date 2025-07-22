package com.company.drools.api.exception;

import com.company.drools.api.dto.ErrorResponse;
import com.company.drools.api.dto.RuleExecutionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(RuleNotFoundException.class)
  public ResponseEntity<RuleExecutionResponse> handleRuleNotFoundException(RuleNotFoundException ex) {
    log.warn("Rule not found: {}", ex.getRuleId());
    
    RuleExecutionResponse response = RuleExecutionResponse.failure(
        ex.getRuleId(),
        "RULE_NOT_FOUND",
        ex.getMessage()
    );
    
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
  }

  @ExceptionHandler(RuleExecutionException.class)
  public ResponseEntity<RuleExecutionResponse> handleRuleExecutionException(RuleExecutionException ex) {
    log.error("Rule execution failed for rule: {}", ex.getRuleId(), ex);
    
    RuleExecutionResponse response = RuleExecutionResponse.failure(
        ex.getRuleId(),
        "RULE_EXECUTION_ERROR",
        ex.getMessage()
    );
    
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<RuleExecutionResponse> handleValidationException(MethodArgumentNotValidException ex) {
    log.warn("Validation failed: {}", ex.getMessage());
    
    String errors = ex.getBindingResult().getFieldErrors().stream()
        .map(FieldError::getDefaultMessage)
        .collect(Collectors.joining(", "));
    
    RuleExecutionResponse response = RuleExecutionResponse.failure(
        null,
        "INVALID_INPUT",
        "Request validation failed",
        errors
    );
    
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
  }

  @ExceptionHandler(TimeoutException.class)
  public ResponseEntity<RuleExecutionResponse> handleTimeoutException(TimeoutException ex) {
    log.error("Operation timed out: {} after {}s", ex.getOperation(), ex.getTimeoutSeconds(), ex);
    
    RuleExecutionResponse response = RuleExecutionResponse.failure(
        null,
        "TIMEOUT_ERROR",
        String.format("Operation '%s' timed out after %d seconds", ex.getOperation(), ex.getTimeoutSeconds())
    );
    
    return ResponseEntity.status(HttpStatus.REQUEST_TIMEOUT).body(response);
  }

  @ExceptionHandler(CircuitBreakerException.class)
  public ResponseEntity<RuleExecutionResponse> handleCircuitBreakerException(CircuitBreakerException ex) {
    log.error("Circuit breaker {} is {}: {}", ex.getCircuitBreakerName(), ex.getState(), ex.getMessage(), ex);
    
    RuleExecutionResponse response = RuleExecutionResponse.failure(
        null,
        "SERVICE_UNAVAILABLE",
        String.format("External service '%s' is temporarily unavailable (%s). Please try again later.", 
                     ex.getCircuitBreakerName(), ex.getState().toLowerCase())
    );
    
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<RuleExecutionResponse> handleIllegalArgumentException(IllegalArgumentException ex) {
    log.warn("Invalid argument: {}", ex.getMessage());
    
    RuleExecutionResponse response = RuleExecutionResponse.failure(
        null,
        "INVALID_INPUT",
        ex.getMessage()
    );
    
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<RuleExecutionResponse> handleGenericException(Exception ex) {
    log.error("Unexpected error occurred", ex);
    
    RuleExecutionResponse response = RuleExecutionResponse.failure(
        null,
        "INTERNAL_ERROR",
        "An unexpected error occurred",
        "Please contact support if this persists"
    );
    
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
  }
}