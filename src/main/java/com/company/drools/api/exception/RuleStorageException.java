package com.company.drools.api.exception;

/** Thrown when a rule storage operation (S3 or local file) fails unrecoverably. */
public class RuleStorageException extends RuntimeException {

  public RuleStorageException(String message, Throwable cause) {
    super(message, cause);
  }
}
