package com.company.drools.api.exception;

/** Exception thrown when circuit breaker is open and calls are not permitted */
public class CircuitBreakerException extends RuntimeException {

  private final String circuitBreakerName;
  private final String state;

  public CircuitBreakerException(String circuitBreakerName, String state) {
    super(
        String.format(
            "Circuit breaker '%s' is %s - calls not permitted", circuitBreakerName, state));
    this.circuitBreakerName = circuitBreakerName;
    this.state = state;
  }

  public CircuitBreakerException(String circuitBreakerName, String state, Throwable cause) {
    super(
        String.format(
            "Circuit breaker '%s' is %s - calls not permitted", circuitBreakerName, state),
        cause);
    this.circuitBreakerName = circuitBreakerName;
    this.state = state;
  }

  public String getCircuitBreakerName() {
    return circuitBreakerName;
  }

  public String getState() {
    return state;
  }
}
