package com.company.drools.api.exception;

/**
 * Thrown when the service cannot accept work right now and should shed load — e.g. the
 * rule-execution thread pool is saturated (queue full and all threads busy). Maps to HTTP 503 so
 * callers back off and retry, rather than running the rule body on the request thread (which would
 * bypass the execution timeout).
 */
public class ServiceUnavailableException extends RuntimeException {

  public ServiceUnavailableException(String message) {
    super(message);
  }

  public ServiceUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
